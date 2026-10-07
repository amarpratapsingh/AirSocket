package com.airsocket;

import com.airsocket.crypto.Crypto;
import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import com.airsocket.transfer.Chunk;
import com.airsocket.transfer.TransferMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class SenderReceiverTest
{
    @TempDir
    public Path tempDir;

    private File sourceFile;
    private byte[] sourceContent;
    private Receiver receiver;
    private Thread receiverThread;
    private int receiverPort = 19191;

    @BeforeEach
    public void setUp() throws Exception
    {
        // Generate a random 2MB source file
        sourceFile = tempDir.resolve("source.bin").toFile();
        sourceContent = new byte[2 * 1024 * 1024];
        new SecureRandom().nextBytes(sourceContent);
        try (FileOutputStream fos = new FileOutputStream(sourceFile))
        {
            fos.write(sourceContent);
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
            catch (InterruptedException e)
            {
                // Ignore
            }
        }
        deleteIfExists(tempDir.resolve("source.bin").toFile());
        deleteIfExists(tempDir.resolve("source.bin.part").toFile());
        deleteIfExists(tempDir.resolve("source.bin.part.meta").toFile());
        deleteIfExists(tempDir.resolve("checksum-test.bin").toFile());
        deleteIfExists(tempDir.resolve("checksum-test.bin.part").toFile());
        deleteIfExists(tempDir.resolve("checksum-test.bin.part.meta").toFile());
    }

    private void deleteIfExists(File file)
    {
        if (file != null && file.exists())
        {
            file.delete();
        }
    }

    private void waitForFile(File file, long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!file.exists() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }
    }

    private void startReceiver(Receiver r) throws Exception
    {
        this.receiver = r;
        this.receiverThread = Thread.ofVirtual().name("test-receiver").start(() ->
        {
            try
            {
                r.start();
            }
            catch (Exception ignored)
            {
            }
        });
        assertTrue(r.awaitReady(5, TimeUnit.SECONDS), "Receiver should bind within 5 seconds");
    }

    @Test
    public void testStandardTransfer() throws Exception
    {
        Path downloadDir = tempDir.resolve("standard-download");
        startReceiver(new Receiver(receiverPort, false, "", false, downloadDir));

        Sender.sendFile("127.0.0.1", receiverPort, sourceFile, false, "", false, false);

        File receivedFile = downloadDir.resolve("source.bin").toFile();
        waitForFile(receivedFile, 3000);

        assertTrue(receivedFile.exists(), "Received file should exist");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should be identical");
    }

    @Test
    public void testEncryptedTransfer() throws Exception
    {
        int encPort = 19199;
        String passphrase = "test-passphrase";
        Path encDir = tempDir.resolve("enc_test_dest");
        startReceiver(new Receiver(encPort, true, passphrase, false, encDir));

        Sender.sendFile("127.0.0.1", encPort, sourceFile, true, passphrase, false, false);

        File receivedFile = encDir.resolve("source.bin").toFile();
        waitForFile(receivedFile, 3000);

        assertTrue(receivedFile.exists(), "Received file should exist");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should be identical");
    }

    @Test
    public void testResumeTransfer() throws Exception
    {
        Path resumeDir = tempDir.resolve("resume-download");
        Files.createDirectories(resumeDir);

        File partFile = resumeDir.resolve("source.bin.part").toFile();
        File metaFile = resumeDir.resolve("source.bin.part.meta").toFile();
        int offset = 1 * 1024 * 1024;
        byte[] partialContent = Arrays.copyOfRange(sourceContent, 0, offset);

        try (FileOutputStream fos = new FileOutputStream(partFile))
        {
            fos.write(partialContent);
        }

        try (FileOutputStream fos = new FileOutputStream(metaFile))
        {
            fos.write(String.valueOf(offset).getBytes("UTF-8"));
        }

        startReceiver(new Receiver(receiverPort, false, "", true, resumeDir));

        Sender.sendFile("127.0.0.1", receiverPort, sourceFile, false, "", true, false);

        File receivedFile = resumeDir.resolve("source.bin").toFile();
        waitForFile(receivedFile, 3000);

        assertTrue(receivedFile.exists(), "Received file should exist after resume completion");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Full contents should be identical");
    }

    @Test
    public void testChecksumMismatchIsRejected() throws Exception
    {
        int checksumPort = 19192;
        File targetFile = tempDir.resolve("checksum-test.bin").toFile();
        targetFile.delete();

        startReceiver(new Receiver(checksumPort, false, "", false, tempDir));

        try (Socket socket = new Socket("127.0.0.1", checksumPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            byte[] nameBytes = "checksum-test.bin".getBytes("UTF-8");
            byte[] badChecksum = new byte[32];

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(1);
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(16L);
            dataOut.writeInt(badChecksum.length);
            dataOut.write(badChecksum);
            dataOut.flush();

            assertEquals(0x06, socket.getInputStream().read(), "Receiver should acknowledge the metadata handshake");

            dataOut.write(new byte[16]);
            dataOut.flush();
            Thread.sleep(200);
        }

        assertFalse(targetFile.exists(), "Receiver should reject tampered payloads and never finalize the file");
        deleteIfExists(tempDir.resolve("checksum-test.bin.part").toFile());
        deleteIfExists(tempDir.resolve("checksum-test.bin.part.meta").toFile());
    }

    @Test
    public void testTransferReportsCompletionSummary() throws Exception
    {
        Path downloadDir = tempDir.resolve("summary-download");
        startReceiver(new Receiver(receiverPort, false, "", false, downloadDir));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try
        {
            System.setOut(new PrintStream(captured, true, "UTF-8"));
            Sender.sendFile("127.0.0.1", receiverPort, sourceFile, false, "", false, false);
        }
        finally
        {
            System.setOut(originalOut);
        }

        String output = captured.toString("UTF-8");
        assertTrue(output.contains("Transfer complete") || output.contains("Complete"),
            "Sender should print a final transfer summary after finishing");
    }

    @Test
    public void testPathTraversalAttemptWithDotDot() throws Exception
    {
        int traversalPort = 19193;
        Path customDir = tempDir.resolve("safe_dest");
        startReceiver(new Receiver(traversalPort, false, "", false, customDir));

        try (Socket socket = new Socket("127.0.0.1", traversalPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            byte[] nameBytes = "../../traversal_attack.bin".getBytes("UTF-8");
            byte[] dummyChecksum = new byte[32];

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(1);
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(16L);
            dataOut.writeInt(dummyChecksum.length);
            dataOut.write(dummyChecksum);
            dataOut.flush();

            // Receiver must reject and close connection without sending ACK 0x06
            int ack = socket.getInputStream().read();
            assertNotEquals(0x06, ack, "Receiver must not ACK a path traversal filename");
        }

        File escapedFile = tempDir.resolve("traversal_attack.bin").toFile();
        assertFalse(escapedFile.exists(), "Attacked file should not be created outside destination");
        File destFile = customDir.resolve("traversal_attack.bin").toFile();
        assertFalse(destFile.exists(), "Attacked file should not be created in destination");
    }

    @Test
    public void testPathTraversalAttemptWithSlash() throws Exception
    {
        int traversalPort = 19194;
        Path customDir = tempDir.resolve("safe_dest2");
        startReceiver(new Receiver(traversalPort, false, "", false, customDir));

        try (Socket socket = new Socket("127.0.0.1", traversalPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            byte[] nameBytes = "nested/slash_attack.bin".getBytes("UTF-8");
            byte[] dummyChecksum = new byte[32];

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(1);
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(16L);
            dataOut.writeInt(dummyChecksum.length);
            dataOut.write(dummyChecksum);
            dataOut.flush();

            int ack = socket.getInputStream().read();
            assertNotEquals(0x06, ack, "Receiver must not ACK a filename containing directory slashes");
        }
    }

    @Test
    public void testCustomDownloadDirectory() throws Exception
    {
        int customPort = 19195;
        Path customDir = tempDir.resolve("isolated_downloads");
        startReceiver(new Receiver(customPort, false, "", false, customDir));

        Sender.sendFile("127.0.0.1", customPort, sourceFile, false, "", false, false);

        File receivedFile = customDir.resolve("source.bin").toFile();
        waitForFile(receivedFile, 3000);

        assertTrue(receivedFile.exists(), "Received file should exist in the custom download directory");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should match exactly");
    }

    @Test
    public void testEncryptedHandshakeConcealsMetadataOnWire() throws Exception
    {
        int mockPort = 19196;
        java.net.ServerSocket mockServer = new java.net.ServerSocket(mockPort);
        mockServer.setReuseAddress(true);

        Thread senderThread = new Thread(() ->
        {
            try
            {
                Sender.sendFile("127.0.0.1", mockPort, sourceFile, true, "wire-secret", false, false);
            }
            catch (Exception e)
            {
                // Expected failure when mock listener closes
            }
        });
        senderThread.start();

        try (Socket intercepted = mockServer.accept())
        {
            DataInputStream dataIn = new DataInputStream(intercepted.getInputStream());
            int magic = dataIn.readInt();
            assertEquals(0x41525354, magic, "Magic should be ARST");

            int version = dataIn.readUnsignedByte();
            assertTrue(version == 2 || version == 4, "Protocol version 2 or 4 should be used for encrypted transfers");

            // Read the next 256 bytes from the wire
            byte[] wireBytes = new byte[256];
            int read = intercepted.getInputStream().read(wireBytes);
            assertTrue(read > 0);

            String wireAscii = new String(wireBytes, 0, read, StandardCharsets.ISO_8859_1);
            assertFalse(wireAscii.contains("source.bin"),
                "Wire payload must NOT leak the plain filename 'source.bin'");
        }
        finally
        {
            mockServer.close();
            senderThread.join(1000);
        }
    }

    @Test
    public void testEncryptedTransferWithWrongPassphraseFails() throws Exception
    {
        int securePort = 19197;
        Path downloadDir = tempDir.resolve("wrong_pass_dest");
        startReceiver(new Receiver(securePort, true, "correct-password", false, downloadDir));

        assertThrows(Exception.class, () ->
        {
            Sender.sendFile("127.0.0.1", securePort, sourceFile, true, "wrong-password", false, false);
        }, "Sender should throw an exception when receiver rejects wrong passphrase");

        File partFile = downloadDir.resolve("source.bin.part").toFile();
        File finalFile = downloadDir.resolve("source.bin").toFile();
        assertFalse(finalFile.exists(), "Final file must never be created with wrong passphrase");
        assertFalse(partFile.exists(), "Part file must never be created when authentication fails at handshake");
    }

    @Test
    public void testUnencryptedSenderRejectedByEncryptedReceiver() throws Exception
    {
        int securePort = 19198;
        Path downloadDir = tempDir.resolve("unenc_dest");
        startReceiver(new Receiver(securePort, true, "secret-phrase", false, downloadDir));

        assertThrows(Exception.class, () ->
        {
            Sender.sendFile("127.0.0.1", securePort, sourceFile, false, "", false, false);
        }, "Unencrypted sender should be rejected by receiver requiring encryption");
    }

    @Test
    public void testEncryptedTransferWithCharArrayPassphrase() throws Exception
    {
        int encPort = 19187;
        char[] passphrase = "char-array-secret-passphrase".toCharArray();
        Path encDir = tempDir.resolve("enc_char_dest");
        startReceiver(new Receiver(encPort, true, passphrase, false, encDir));

        Sender.sendFile("127.0.0.1", encPort, sourceFile, true, passphrase, false, false);

        File receivedFile = encDir.resolve("source.bin").toFile();
        waitForFile(receivedFile, 3000);

        assertTrue(receivedFile.exists(), "Received file should exist");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should be identical");
        Crypto.wipe(passphrase);
    }

    @Test
    public void testNegativeFileSizeMetadataIsRejected() throws Exception
    {
        int testPort = 19181;
        Path destDir = tempDir.resolve("neg_size_dest");
        startReceiver(new Receiver(testPort, false, "", false, destDir));

        try (Socket socket = new Socket("127.0.0.1", testPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            byte[] nameBytes = "neg_size.bin".getBytes(StandardCharsets.UTF_8);
            byte[] dummyChecksum = new byte[32];

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(1);
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(-100L); // Negative file size
            dataOut.writeInt(dummyChecksum.length);
            dataOut.write(dummyChecksum);
            dataOut.flush();

            int ack = socket.getInputStream().read();
            assertNotEquals(0x06, ack, "Receiver must reject negative file size and not send ACK");
        }
    }

    @Test
    public void testInvalidChecksumLengthMetadataIsRejected() throws Exception
    {
        int testPort = 19182;
        Path destDir = tempDir.resolve("inv_chk_dest");
        startReceiver(new Receiver(testPort, false, "", false, destDir));

        try (Socket socket = new Socket("127.0.0.1", testPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            byte[] nameBytes = "inv_chk.bin".getBytes(StandardCharsets.UTF_8);

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(1);
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(100L);
            dataOut.writeInt(15); // Invalid SHA-256 length (must be 32)
            dataOut.write(new byte[15]);
            dataOut.flush();

            int ack = socket.getInputStream().read();
            assertNotEquals(0x06, ack, "Receiver must reject invalid checksum length and not send ACK");
        }
    }

    @Test
    public void testInsufficientDiskSpaceRejectsTransfer() throws Exception
    {
        int spacePort = 19183;
        Path spaceDir = tempDir.resolve("space_dest");
        Receiver spaceReceiver = new Receiver(spacePort, false, "", false, spaceDir);
        // Simulate no available disk space
        spaceReceiver.setDiskSpaceValidator((dir, required) -> false);
        startReceiver(spaceReceiver);

        assertThrows(Exception.class, () ->
        {
            Sender.sendFile("127.0.0.1", spacePort, sourceFile, false, "", false, false);
        }, "Sender should throw an exception when receiver rejects transfer due to insufficient disk space");

        File receivedFile = spaceDir.resolve("source.bin").toFile();
        File partFile = spaceDir.resolve("source.bin.part").toFile();
        assertFalse(receivedFile.exists(), "Target file must not be created");
        assertFalse(partFile.exists(), "Part file must not be created when disk space check fails");
    }

    @Test
    public void testFileCollisionHandlingPreservesExistingFile() throws Exception
    {
        int collisionPort = 19184;
        Path collisionDir = tempDir.resolve("collision_dest");
        Files.createDirectories(collisionDir);

        // Pre-create source.bin with existing content
        File existingFile = collisionDir.resolve("source.bin").toFile();
        byte[] originalContent = "Original Unmodified Content".getBytes(StandardCharsets.UTF_8);
        Files.write(existingFile.toPath(), originalContent);

        startReceiver(new Receiver(collisionPort, false, "", false, collisionDir));

        // Transfer sourceFile (which is named "source.bin" and has 2MB of random data)
        Sender.sendFile("127.0.0.1", collisionPort, sourceFile, false, "", false, false);

        File collisionFile1 = collisionDir.resolve("source (1).bin").toFile();
        waitForFile(collisionFile1, 3000);

        // 1. Existing file must still exist and must NOT have been overwritten
        assertTrue(existingFile.exists(), "Original existing file must still exist");
        assertArrayEquals(originalContent, Files.readAllBytes(existingFile.toPath()),
            "Original existing file content must not be modified or overwritten");

        // 2. Transferred file must be saved as source (1).bin
        assertTrue(collisionFile1.exists(), "Colliding file should be saved as source (1).bin");
        assertEquals(sourceFile.length(), collisionFile1.length(), "Sizes should match");
        assertArrayEquals(sourceContent, Files.readAllBytes(collisionFile1.toPath()),
            "New file content should match source content");

        // 3. Send again - should create source (2).bin
        Sender.sendFile("127.0.0.1", collisionPort, sourceFile, false, "", false, false);
        File collisionFile2 = collisionDir.resolve("source (2).bin").toFile();
        waitForFile(collisionFile2, 3000);
        assertTrue(collisionFile2.exists(), "Subsequent collision should be saved as source (2).bin");
    }

    @Test
    public void testTransferIdIsPersistedInMetaFile() throws Exception
    {
        int idPort = 19185;
        Path idDir = tempDir.resolve("id_dest");
        Files.createDirectories(idDir);
        File metaFile = idDir.resolve("test_id.bin.part.meta").toFile();

        Receiver idReceiver = new Receiver(idPort, false, "", true, idDir);
        startReceiver(idReceiver);

        try (Socket socket = new Socket("127.0.0.1", idPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            String testId = "custom-test-uuid-12345";
            byte[] nameBytes = "test_id.bin".getBytes(StandardCharsets.UTF_8);
            byte[] checksum = new byte[32];
            byte[] idBytes = testId.getBytes(StandardCharsets.UTF_8);

            dataOut.writeInt(0x41525354);
            dataOut.writeByte(3); // Protocol version 3 with transfer ID
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(1000L);
            dataOut.writeInt(checksum.length);
            dataOut.write(checksum);
            dataOut.writeInt(idBytes.length);
            dataOut.write(idBytes);
            dataOut.flush();

            assertEquals(0x06, socket.getInputStream().read(), "Receiver should ACK handshake");

            // Send partial data (500 bytes) and disconnect
            dataOut.write(new byte[500]);
            dataOut.flush();
        }

        // Wait a moment for receiver to flush meta
        Thread.sleep(300);

        assertTrue(metaFile.exists(), "Meta file should be created for interrupted transfer");
        String metaContent = Files.readString(metaFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(metaContent.contains("transferId=custom-test-uuid-12345"),
            "Meta file must contain the transfer ID");
        assertTrue(metaContent.contains("offset=500"),
            "Meta file must contain the transferred byte offset");
    }

    @Test
    public void testEncryptedStreamingPayloadTamperingFailsAEADTagVerification() throws Exception
    {
        int encTamperPort = 19186;
        Path encTamperDir = tempDir.resolve("enc_tamper_dest");
        String passphrase = "tamper-passphrase";
        startReceiver(new Receiver(encTamperPort, true, passphrase, false, encTamperDir));

        // Use a mock sender that properly performs handshake, but corrupts the encrypted payload stream
        byte[] salt = new byte[Crypto.SALT_LENGTH];
        byte[] ivMeta = new byte[Crypto.IV_LENGTH];
        byte[] ivData = new byte[Crypto.IV_LENGTH];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(ivMeta);
        new SecureRandom().nextBytes(ivData);

        javax.crypto.spec.SecretKeySpec secretKey = Crypto.deriveKey(passphrase.toCharArray(), salt);
        byte[] nameBytes = "tamper_test.bin".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "Confidential Top Secret Data".getBytes(StandardCharsets.UTF_8);
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext);

        ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
        try (DataOutputStream metaDataOut = new DataOutputStream(metaOut))
        {
            metaDataOut.writeInt(nameBytes.length);
            metaDataOut.write(nameBytes);
            metaDataOut.writeLong(plaintext.length);
            metaDataOut.writeInt(checksum.length);
            metaDataOut.write(checksum);
            metaDataOut.writeInt(10);
            metaDataOut.write("id-1234567".getBytes(StandardCharsets.UTF_8));
        }
        javax.crypto.Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, javax.crypto.Cipher.ENCRYPT_MODE);
        byte[] encryptedMeta = metaCipher.doFinal(metaOut.toByteArray());

        try (Socket socket = new Socket("127.0.0.1", encTamperPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            dataOut.writeInt(0x41525354);
            dataOut.writeByte(2);
            dataOut.write(salt);
            dataOut.write(ivMeta);
            dataOut.write(ivData);
            dataOut.writeInt(encryptedMeta.length);
            dataOut.write(encryptedMeta);
            dataOut.flush();

            assertEquals(0x06, socket.getInputStream().read(), "Receiver should ACK metadata handshake");

            // Encrypt payload
            javax.crypto.Cipher dataCipher = Crypto.getCipher(secretKey, ivData, javax.crypto.Cipher.ENCRYPT_MODE);
            byte[] ciphertext = dataCipher.doFinal(plaintext);

            // Tamper with one byte of the ciphertext
            ciphertext[2] ^= 0x01;

            out.write(ciphertext);
            out.flush();
            socket.shutdownOutput();

            // Give receiver time to detect AEAD tag failure
            Thread.sleep(300);
        }

        File targetFile = encTamperDir.resolve("tamper_test.bin").toFile();
        File partFile = encTamperDir.resolve("tamper_test.bin.part").toFile();
        File metaFile = encTamperDir.resolve("tamper_test.bin.part.meta").toFile();

        assertFalse(targetFile.exists(), "Target file must never be finalized when AEAD authentication fails");
        assertFalse(partFile.exists(), "Part file must be purged when AEAD tag mismatch occurs");
        assertFalse(metaFile.exists(), "Meta file must be purged when AEAD tag mismatch occurs");
    }

    @Test
    public void testEncryptedResumeWithChunkBasedAesGcm() throws Exception
    {
        int encResumePort = 19171;
        Path encResumeDir = tempDir.resolve("enc_resume_dest");
        Files.createDirectories(encResumeDir);
        String passphrase = "enc-resume-password";
        startReceiver(new Receiver(encResumePort, true, passphrase, true, encResumeDir));

        byte[] salt = new byte[Crypto.SALT_LENGTH];
        byte[] ivMeta = new byte[Crypto.IV_LENGTH];
        byte[] ivData = new byte[Crypto.IV_LENGTH];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(ivMeta);
        new SecureRandom().nextBytes(ivData);

        javax.crypto.spec.SecretKeySpec secretKey = Crypto.deriveKey(passphrase.toCharArray(), salt);
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(sourceContent);
        int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;

        ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
        try (DataOutputStream metaDataOut = new DataOutputStream(metaOut))
        {
            byte[] nameBytes = sourceFile.getName().getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(nameBytes.length);
            metaDataOut.write(nameBytes);
            metaDataOut.writeLong(sourceFile.length());
            metaDataOut.writeInt(checksum.length);
            metaDataOut.write(checksum);
            byte[] idBytes = "mock-uuid-resume".getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(idBytes.length);
            metaDataOut.write(idBytes);
            metaDataOut.writeInt(chunkSize);
        }
        javax.crypto.Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, javax.crypto.Cipher.ENCRYPT_MODE);
        byte[] encryptedMeta = metaCipher.doFinal(metaOut.toByteArray());

        try (Socket socket = new Socket("127.0.0.1", encResumePort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            dataOut.writeInt(0x41525354);
            dataOut.writeByte(2);
            dataOut.write(salt);
            dataOut.write(ivMeta);
            dataOut.write(ivData);
            dataOut.writeInt(encryptedMeta.length);
            dataOut.write(encryptedMeta);
            dataOut.flush();

            assertEquals(0x06, socket.getInputStream().read(), "Receiver should ACK handshake");

            // Send first 16 chunks (1 MB out of 2 MB)
            for (int i = 0; i < 16; i++)
            {
                byte[] chunkIv = Crypto.deriveChunkIv(ivData, i);
                javax.crypto.Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, javax.crypto.Cipher.ENCRYPT_MODE);
                byte[] encryptedChunk = chunkCipher.doFinal(sourceContent, i * chunkSize, chunkSize);
                dataOut.writeInt(encryptedChunk.length);
                dataOut.write(encryptedChunk);
            }
            dataOut.flush();
            // Abruptly close socket to simulate mid-transfer interruption
        }

        Thread.sleep(300);

        File partFile = encResumeDir.resolve("source.bin.part").toFile();
        File metaFile = encResumeDir.resolve("source.bin.part.meta").toFile();
        assertTrue(partFile.exists(), "Part file should exist after interrupted transfer");
        assertEquals(16 * chunkSize, partFile.length(), "Part file should contain exactly 1MB of plaintext");
        assertTrue(metaFile.exists(), "Meta file should exist after interrupted transfer");

        TransferMetadata loaded = TransferMetadata.load(metaFile.toPath());
        assertNotNull(loaded, "Metadata should be parsed cleanly");
        assertEquals(16 * chunkSize, loaded.offset(), "Recorded offset should be 1MB");
        assertEquals(chunkSize, loaded.chunkSize(), "Chunk size should be preserved");
        assertTrue(loaded.encrypted(), "Transfer should be marked as encrypted");

        // Now resume the transfer using Sender.sendFile
        Sender.sendFile("127.0.0.1", encResumePort, sourceFile, true, passphrase, true, false);

        File finalFile = encResumeDir.resolve("source.bin").toFile();
        waitForFile(finalFile, 3000);

        assertTrue(finalFile.exists(), "Final file must exist after resume completion");
        assertEquals(sourceFile.length(), finalFile.length(), "File size must match original");
        assertArrayEquals(sourceContent, Files.readAllBytes(finalFile.toPath()), "Content must match original byte-for-byte");
        assertFalse(partFile.exists(), "Part file must be cleaned up");
        assertFalse(metaFile.exists(), "Meta file must be cleaned up");
    }

    @Test
    public void testEncryptedResumeRejectsTamperedChunk() throws Exception
    {
        int tamperPort = 19172;
        Path tamperDir = tempDir.resolve("enc_resume_tamper_dest");
        Files.createDirectories(tamperDir);
        String passphrase = "tamper-passphrase";
        startReceiver(new Receiver(tamperPort, true, passphrase, true, tamperDir));

        byte[] salt1 = new byte[Crypto.SALT_LENGTH];
        byte[] ivMeta1 = new byte[Crypto.IV_LENGTH];
        byte[] ivData1 = new byte[Crypto.IV_LENGTH];
        new SecureRandom().nextBytes(salt1);
        new SecureRandom().nextBytes(ivMeta1);
        new SecureRandom().nextBytes(ivData1);

        javax.crypto.spec.SecretKeySpec secretKey1 = Crypto.deriveKey(passphrase.toCharArray(), salt1);
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(sourceContent);
        int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;

        ByteArrayOutputStream metaOut1 = new ByteArrayOutputStream();
        try (DataOutputStream metaDataOut = new DataOutputStream(metaOut1))
        {
            byte[] nameBytes = sourceFile.getName().getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(nameBytes.length);
            metaDataOut.write(nameBytes);
            metaDataOut.writeLong(sourceFile.length());
            metaDataOut.writeInt(checksum.length);
            metaDataOut.write(checksum);
            byte[] idBytes = "mock-uuid-tamper".getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(idBytes.length);
            metaDataOut.write(idBytes);
            metaDataOut.writeInt(chunkSize);
        }
        javax.crypto.Cipher metaCipher1 = Crypto.getCipher(secretKey1, ivMeta1, javax.crypto.Cipher.ENCRYPT_MODE);
        byte[] encryptedMeta1 = metaCipher1.doFinal(metaOut1.toByteArray());

        // Send 1 valid chunk (chunk 0) and disconnect
        try (Socket socket = new Socket("127.0.0.1", tamperPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            dataOut.writeInt(0x41525354);
            dataOut.writeByte(2);
            dataOut.write(salt1);
            dataOut.write(ivMeta1);
            dataOut.write(ivData1);
            dataOut.writeInt(encryptedMeta1.length);
            dataOut.write(encryptedMeta1);
            dataOut.flush();

            assertEquals(0x06, socket.getInputStream().read());

            byte[] chunkIv = Crypto.deriveChunkIv(ivData1, 0);
            javax.crypto.Cipher chunkCipher = Crypto.getCipher(secretKey1, chunkIv, javax.crypto.Cipher.ENCRYPT_MODE);
            byte[] encryptedChunk = chunkCipher.doFinal(sourceContent, 0, chunkSize);
            dataOut.writeInt(encryptedChunk.length);
            dataOut.write(encryptedChunk);
            dataOut.flush();
        }

        Thread.sleep(300);

        File partFile = tamperDir.resolve("source.bin.part").toFile();
        File metaFile = tamperDir.resolve("source.bin.part.meta").toFile();
        assertTrue(partFile.exists());
        assertTrue(metaFile.exists());

        // Now resume with a new session, but send a corrupted chunk 1
        byte[] salt2 = new byte[Crypto.SALT_LENGTH];
        byte[] ivMeta2 = new byte[Crypto.IV_LENGTH];
        byte[] ivData2 = new byte[Crypto.IV_LENGTH];
        new SecureRandom().nextBytes(salt2);
        new SecureRandom().nextBytes(ivMeta2);
        new SecureRandom().nextBytes(ivData2);

        javax.crypto.spec.SecretKeySpec secretKey2 = Crypto.deriveKey(passphrase.toCharArray(), salt2);
        ByteArrayOutputStream metaOut2 = new ByteArrayOutputStream();
        try (DataOutputStream metaDataOut = new DataOutputStream(metaOut2))
        {
            byte[] nameBytes = sourceFile.getName().getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(nameBytes.length);
            metaDataOut.write(nameBytes);
            metaDataOut.writeLong(sourceFile.length());
            metaDataOut.writeInt(checksum.length);
            metaDataOut.write(checksum);
            byte[] idBytes = "mock-uuid-tamper-2".getBytes(StandardCharsets.UTF_8);
            metaDataOut.writeInt(idBytes.length);
            metaDataOut.write(idBytes);
            metaDataOut.writeInt(chunkSize);
        }
        javax.crypto.Cipher metaCipher2 = Crypto.getCipher(secretKey2, ivMeta2, javax.crypto.Cipher.ENCRYPT_MODE);
        byte[] encryptedMeta2 = metaCipher2.doFinal(metaOut2.toByteArray());

        try (Socket socket = new Socket("127.0.0.1", tamperPort);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            dataOut.writeInt(0x41525354);
            dataOut.writeByte(2);
            dataOut.write(salt2);
            dataOut.write(ivMeta2);
            dataOut.write(ivData2);
            dataOut.writeInt(encryptedMeta2.length);
            dataOut.write(encryptedMeta2);
            dataOut.flush();

            // Read RESUME response: expect "RESUME:65536\n"
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            StringBuilder resp = new StringBuilder();
            int b;
            while ((b = dataIn.read()) != -1 && b != '\n')
            {
                resp.append((char) b);
            }
            assertTrue(resp.toString().startsWith("RESUME:"), "Expected RESUME from receiver");

            // Prepare chunk 1 and tamper with a byte
            byte[] chunkIv = Crypto.deriveChunkIv(ivData2, 1);
            javax.crypto.Cipher chunkCipher = Crypto.getCipher(secretKey2, chunkIv, javax.crypto.Cipher.ENCRYPT_MODE);
            byte[] encryptedChunk = chunkCipher.doFinal(sourceContent, chunkSize, chunkSize);
            encryptedChunk[10] ^= 0x01; // Tamper with ciphertext!

            dataOut.writeInt(encryptedChunk.length);
            dataOut.write(encryptedChunk);
            dataOut.flush();

            Thread.sleep(300);
        }

        File targetFile = tamperDir.resolve("source.bin").toFile();
        assertFalse(targetFile.exists(), "Target file must never be finalized when AEAD tag verification fails");
        assertFalse(partFile.exists(), "Part file must be deleted when AEAD tag verification fails");
        assertFalse(metaFile.exists(), "Meta file must be deleted when AEAD tag verification fails");
    }

    @Test
    public void testResumeWithCorruptedMetaFileRecoversGracefully() throws Exception
    {
        int corruptPort = 19173;
        Path corruptDir = tempDir.resolve("corrupt_meta_dest");
        Files.createDirectories(corruptDir);

        File partFile = corruptDir.resolve("source.bin.part").toFile();
        File metaFile = corruptDir.resolve("source.bin.part.meta").toFile();
        Files.write(partFile.toPath(), new byte[500]);
        Files.writeString(metaFile.toPath(), "MALFORMED_GARBAGE\0\0\0\n");

        startReceiver(new Receiver(corruptPort, false, "", true, corruptDir));

        // When meta is corrupted, receiver should restart transfer from offset 0
        Sender.sendFile("127.0.0.1", corruptPort, sourceFile, false, "", true, false);

        File finalFile = corruptDir.resolve("source.bin").toFile();
        waitForFile(finalFile, 3000);

        assertTrue(finalFile.exists(), "Final file should exist after graceful recovery");
        assertEquals(sourceFile.length(), finalFile.length());
        assertArrayEquals(sourceContent, Files.readAllBytes(finalFile.toPath()));
        assertFalse(partFile.exists());
        assertFalse(metaFile.exists());
    }

    @Test
    public void testResumeRejectsMismatchedFileMetadata() throws Exception
    {
        int mismatchPort = 19174;
        Path mismatchDir = tempDir.resolve("mismatch_meta_dest");
        Files.createDirectories(mismatchDir);

        File partFile = mismatchDir.resolve("source.bin.part").toFile();
        File metaFile = mismatchDir.resolve("source.bin.part.meta").toFile();
        Files.write(partFile.toPath(), new byte[1000]);

        // Write meta file with mismatched file size (9999999 vs 2097152)
        TransferMetadata mismatchMeta = TransferMetadata.create(
            "mismatch-id",
            "source.bin",
            9999999L,
            new byte[32],
            Chunk.DEFAULT_CHUNK_SIZE,
            1000L,
            false,
            null, null, null
        );
        mismatchMeta.save(metaFile.toPath());

        startReceiver(new Receiver(mismatchPort, false, "", true, mismatchDir));

        // Transfer should reject resume offset and cleanly transfer from offset 0
        Sender.sendFile("127.0.0.1", mismatchPort, sourceFile, false, "", true, false);

        File finalFile = mismatchDir.resolve("source.bin").toFile();
        waitForFile(finalFile, 3000);

        assertTrue(finalFile.exists(), "Final file should exist");
        assertEquals(sourceFile.length(), finalFile.length());
        assertArrayEquals(sourceContent, Files.readAllBytes(finalFile.toPath()));
        assertFalse(partFile.exists());
        assertFalse(metaFile.exists());
    }
}
