package com.airsocket;

import com.airsocket.crypto.Crypto;
import com.airsocket.protocol.AirSocketProtocolException;
import com.airsocket.protocol.ErrorCode;
import com.airsocket.protocol.Frame;
import com.airsocket.protocol.ProtocolVersion;
import com.airsocket.transfer.Chunk;
import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import com.airsocket.transfer.TransferMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class FailureRecoveryTest
{
    @TempDir
    public Path tempDir;

    private File testSourceFile;
    private byte[] testSourceContent;
    private Receiver receiver;
    private Thread receiverThread;

    @BeforeEach
    public void setUp() throws Exception
    {
        testSourceFile = tempDir.resolve("failure_test.bin").toFile();
        testSourceContent = new byte[1024 * 1024]; // 1 MB
        new SecureRandom().nextBytes(testSourceContent);
        try (FileOutputStream fos = new FileOutputStream(testSourceFile))
        {
            fos.write(testSourceContent);
        }
    }

    @AfterEach
    public void tearDown()
    {
        if (receiver != null)
        {
            receiver.stop();
        }
        if (receiverThread != null)
        {
            try
            {
                receiverThread.join(500);
            }
            catch (InterruptedException ignored)
            {
            }
        }
    }

    private void startReceiver(Receiver r) throws Exception
    {
        this.receiver = r;
        this.receiverThread = Thread.ofVirtual().name("fail-rec-receiver").start(() ->
        {
            try
            {
                r.start();
            }
            catch (Exception ignored)
            {
            }
        });
        assertTrue(r.awaitReady(5, TimeUnit.SECONDS), "Receiver must start within 5s");
    }

    @Test
    public void testMidStreamConnectionDropAndResumeRecovery() throws Exception
    {
        int port = 23001;
        Path destDir = tempDir.resolve("resume_recovery_dest");
        Files.createDirectories(destDir);

        // Pre-create partial state (512 KB transferred out of 1 MB)
        File partFile = destDir.resolve("failure_test.bin.part").toFile();
        File metaFile = destDir.resolve("failure_test.bin.part.meta").toFile();
        int halfSize = 512 * 1024;
        try (FileOutputStream fos = new FileOutputStream(partFile))
        {
            fos.write(testSourceContent, 0, halfSize);
        }

        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(testSourceContent);
        TransferMetadata meta = TransferMetadata.create(
            "recovery-session-id", "failure_test.bin", testSourceContent.length, checksum,
            Chunk.DEFAULT_CHUNK_SIZE, halfSize, false, null, null, null
        );
        meta.save(metaFile.toPath());

        // Start real receiver with --resume enabled
        startReceiver(new Receiver(port, false, "", true, destDir));

        // Sender connects with resume=true, resumes from 512 KB, and finishes!
        Sender.sendFile("127.0.0.1", port, testSourceFile, false, "", true, false,
            ProtocolVersion.V4, 3000, 5000, 3);

        File finalFile = destDir.resolve("failure_test.bin").toFile();
        assertTrue(finalFile.exists(), "Final file must exist after recovery");
        assertEquals(testSourceContent.length, finalFile.length());
        assertArrayEquals(testSourceContent, Files.readAllBytes(finalFile.toPath()));
        assertFalse(partFile.exists(), "Temporary .part file must be removed");
        assertFalse(metaFile.exists(), "Metadata .part.meta file must be removed");
    }

    @Test
    public void testCorruptedPartialMetadataFallback() throws Exception
    {
        int port = 23002;
        Path destDir = tempDir.resolve("corrupt_meta_recovery");
        Files.createDirectories(destDir);

        File partFile = destDir.resolve("failure_test.bin.part").toFile();
        File metaFile = destDir.resolve("failure_test.bin.part.meta").toFile();

        // Write junk to meta file
        Files.writeString(metaFile.toPath(), "MALFORMED_CORRUPTED_METADATA_CONTENT\nNON_KEY_VALUE\n");
        Files.write(partFile.toPath(), new byte[]{1, 2, 3, 4, 5});

        startReceiver(new Receiver(port, false, "", true, destDir));

        // Sender should cleanly transfer and complete even with corrupted pre-existing meta
        Sender.sendFile("127.0.0.1", port, testSourceFile, false, "", true, false,
            ProtocolVersion.V4, 3000, 5000, 1);

        File finalFile = destDir.resolve("failure_test.bin").toFile();
        assertTrue(finalFile.exists(), "Transfer must complete despite corrupted initial metadata");
        assertEquals(testSourceContent.length, finalFile.length());
        assertArrayEquals(testSourceContent, Files.readAllBytes(finalFile.toPath()));
    }

    @Test
    public void testAEADTagTamperingPurgesPartialFiles() throws Exception
    {
        int port = 23003;
        Path destDir = tempDir.resolve("tamper_purge_dest");
        String secret = "recovery-tamper-secret";
        startReceiver(new Receiver(port, true, secret, false, destDir));

        UUID transferId = UUID.randomUUID();
        byte[] salt = new byte[Crypto.SALT_LENGTH];
        byte[] ivMeta = new byte[Crypto.IV_LENGTH];
        byte[] ivData = new byte[Crypto.IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(ivMeta);
        random.nextBytes(ivData);

        SecretKeySpec key = Crypto.deriveKey(secret.toCharArray(), salt);
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(testSourceContent);

        ByteArrayOutputStream metaBaos = new ByteArrayOutputStream();
        try (DataOutputStream metaDos = new DataOutputStream(metaBaos))
        {
            metaDos.writeInt(0x41555448);
            byte[] token = "AirSocket-V4-Token".getBytes(StandardCharsets.UTF_8);
            metaDos.writeInt(token.length);
            metaDos.write(token);
            byte[] name = "tampered.bin".getBytes(StandardCharsets.UTF_8);
            metaDos.writeInt(name.length);
            metaDos.write(name);
            metaDos.writeLong(testSourceContent.length);
            metaDos.writeInt(checksum.length);
            metaDos.write(checksum);
            metaDos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);
        }

        Cipher metaCipher = Crypto.getCipher(key, ivMeta, Cipher.ENCRYPT_MODE);
        byte[] encMeta = metaCipher.doFinal(metaBaos.toByteArray());

        ByteArrayOutputStream initBaos = new ByteArrayOutputStream();
        try (DataOutputStream initDos = new DataOutputStream(initBaos))
        {
            initDos.write(salt);
            initDos.write(ivMeta);
            initDos.write(ivData);
            initDos.writeInt(encMeta.length);
            initDos.write(encMeta);
        }

        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream()))
        {
            Frame initFrame = Frame.handshakeInit(transferId, ProtocolVersion.V4, true, false, initBaos.toByteArray());
            initFrame.writeTo(out);

            // Send chunk 0 encrypted normally
            byte[] chunkIv = Crypto.deriveChunkIv(ivData, 0L);
            Cipher chunkCipher = Crypto.getCipher(key, chunkIv, Cipher.ENCRYPT_MODE);
            byte[] cipherChunk = chunkCipher.doFinal(testSourceContent, 0, Chunk.DEFAULT_CHUNK_SIZE);

            // Tamper with chunk ciphertext!
            cipherChunk[10] ^= 0x55;

            Frame tamperedChunk = Frame.chunkData(transferId, 0L, cipherChunk, false);
            tamperedChunk.writeTo(out);
            out.flush();

            Thread.sleep(300);
        }

        File partFile = destDir.resolve("tampered.bin.part").toFile();
        File metaFile = destDir.resolve("tampered.bin.part.meta").toFile();
        File finalFile = destDir.resolve("tampered.bin").toFile();

        assertFalse(finalFile.exists(), "Final file must never be created on tampering");
        assertFalse(partFile.exists(), "Partial .part file must be purged on AEAD authentication tag failure");
        assertFalse(metaFile.exists(), "Partial .meta file must be purged on AEAD authentication tag failure");
    }

    @Test
    public void testInsufficientDiskSpaceRecovery() throws Exception
    {
        int port = 23004;
        Path destDir = tempDir.resolve("space_fail_dest");
        Receiver spaceReceiver = new Receiver(port, false, "", false, destDir);
        spaceReceiver.setDiskSpaceValidator((dir, req) -> false); // Disk is full!
        startReceiver(spaceReceiver);

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            Sender.sendFile("127.0.0.1", port, testSourceFile, false, "", false, false, ProtocolVersion.V4);
        });

        assertEquals(ErrorCode.INSUFFICIENT_SPACE, ex.getErrorCode());
    }

    @Test
    public void testPathTraversalAttemptBlockedWithoutFileCreation() throws Exception
    {
        int port = 23005;
        Path destDir = tempDir.resolve("traversal_block_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID transferId = UUID.randomUUID();
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream()))
        {
            byte[] badName = "../malicious.bin".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(badName.length);
            dos.write(badName);
            dos.writeLong(100L);
            dos.writeInt(0);
            dos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);

            Frame init = Frame.handshakeInit(transferId, ProtocolVersion.V4, false, false, baos.toByteArray());
            init.writeTo(out);

            Frame reply = Frame.readFrom(in);
            assertEquals(ErrorCode.PATH_TRAVERSAL, reply.parseErrorCode());
        }

        assertFalse(destDir.resolve("malicious.bin").toFile().exists());
        assertFalse(destDir.resolve("../malicious.bin").toFile().exists());
    }
}
