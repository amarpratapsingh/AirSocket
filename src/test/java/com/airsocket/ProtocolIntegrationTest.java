package com.airsocket;

import com.airsocket.protocol.AirSocketProtocolException;
import com.airsocket.protocol.ErrorCode;
import com.airsocket.protocol.Frame;
import com.airsocket.protocol.FrameType;
import com.airsocket.protocol.ProtocolVersion;
import com.airsocket.transfer.Chunk;
import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ProtocolIntegrationTest
{
    @TempDir
    public Path tempDir;

    private File sourceFile;
    private byte[] sourceContent;
    private Receiver receiver;
    private Thread receiverThread;

    @BeforeEach
    public void setUp() throws Exception
    {
        sourceFile = tempDir.resolve("protocol_test.bin").toFile();
        sourceContent = new byte[1024 * 1024]; // 1 MB
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
            catch (InterruptedException ignored)
            {
            }
        }
    }

    private void startReceiver(Receiver r) throws Exception
    {
        this.receiver = r;
        this.receiverThread = Thread.ofVirtual().name("proto-receiver").start(() ->
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
    public void testV4EncryptedTransferEndToEnd() throws Exception
    {
        int port = 21001;
        Path destDir = tempDir.resolve("v4_enc_dest");
        String secret = "protocol-v4-super-secret";
        startReceiver(new Receiver(port, true, secret, false, destDir));

        Sender.sendFile("127.0.0.1", port, sourceFile, true, secret, false, false, ProtocolVersion.V4);

        File destFile = destDir.resolve("protocol_test.bin").toFile();
        assertTrue(destFile.exists(), "Target file must exist");
        assertEquals(sourceFile.length(), destFile.length());
        assertArrayEquals(sourceContent, Files.readAllBytes(destFile.toPath()));
    }

    @Test
    public void testV4AuthFailureSendsExplicitErrorFrame() throws Exception
    {
        int port = 21002;
        Path destDir = tempDir.resolve("v4_auth_dest");
        startReceiver(new Receiver(port, true, "correct-passphrase", false, destDir));

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            Sender.sendFile("127.0.0.1", port, sourceFile, true, "wrong-passphrase", false, false, ProtocolVersion.V4);
        });

        assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode(), "Must propagate ErrorCode.AUTH_FAILED");
        assertNotNull(ex.getTransferId(), "Must include transfer ID");
    }

    @Test
    public void testV4InsufficientDiskSpaceSendsExplicitErrorFrame() throws Exception
    {
        int port = 21003;
        Path destDir = tempDir.resolve("v4_space_dest");
        Receiver spaceReceiver = new Receiver(port, false, "", false, destDir);
        spaceReceiver.setDiskSpaceValidator((dir, req) -> false); // Disk is full
        startReceiver(spaceReceiver);

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            Sender.sendFile("127.0.0.1", port, sourceFile, false, "", false, false, ProtocolVersion.V4);
        });

        assertEquals(ErrorCode.INSUFFICIENT_SPACE, ex.getErrorCode(), "Must propagate ErrorCode.INSUFFICIENT_SPACE");
    }

    @Test
    public void testV4PathTraversalSendsExplicitErrorFrame() throws Exception
    {
        int port = 21004;
        Path destDir = tempDir.resolve("v4_path_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID transferId = UUID.randomUUID();
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            byte[] badName = "../../etc_passwd.bin".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(badName.length);
            dos.write(badName);
            dos.writeLong(100L);
            dos.writeInt(32);
            dos.write(new byte[32]);
            dos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);

            Frame initFrame = Frame.handshakeInit(transferId, ProtocolVersion.V4, false, false, baos.toByteArray());
            initFrame.writeTo(out);

            Frame reply = Frame.readFrom(in);
            assertEquals(FrameType.ERROR, reply.type());
            assertEquals(ErrorCode.PATH_TRAVERSAL, reply.parseErrorCode());
        }
    }

    @Test
    public void testV4StateViolationSendsExplicitErrorFrame() throws Exception
    {
        int port = 21005;
        Path destDir = tempDir.resolve("v4_state_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID transferId = UUID.randomUUID();
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            // Sending CHUNK_DATA before HANDSHAKE_INIT
            Frame illegalChunk = Frame.chunkData(transferId, 0L, new byte[]{1, 2, 3}, false);
            illegalChunk.writeTo(out);

            Frame reply = Frame.readFrom(in);
            assertEquals(FrameType.ERROR, reply.type());
            assertEquals(ErrorCode.STATE_VIOLATION, reply.parseErrorCode());
        }
    }

    @Test
    public void testV4TransferIdMismatchSendsExplicitErrorFrame() throws Exception
    {
        int port = 21006;
        Path destDir = tempDir.resolve("v4_id_mismatch_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID handshakeId = UUID.randomUUID();
        UUID differentId = UUID.randomUUID();

        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            byte[] nameBytes = "file.bin".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(nameBytes.length);
            dos.write(nameBytes);
            dos.writeLong(100L);
            dos.writeInt(32);
            dos.write(new byte[32]);
            dos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);

            Frame initFrame = Frame.handshakeInit(handshakeId, ProtocolVersion.V4, false, false, baos.toByteArray());
            initFrame.writeTo(out);

            Frame ack = Frame.readFrom(in);
            assertEquals(FrameType.HANDSHAKE_ACK, ack.type());

            // Send chunk with different transfer ID
            Frame chunkFrame = Frame.chunkData(differentId, 0L, new byte[100], true);
            chunkFrame.writeTo(out);

            Frame reply = Frame.readFrom(in);
            assertEquals(FrameType.ERROR, reply.type());
            assertEquals(ErrorCode.INVALID_TRANSFER_ID, reply.parseErrorCode());
        }
    }

    @Test
    public void testV4AutomaticRetryLoopRecoversFromTransientNetworkDrop() throws Exception
    {
        int serverPort = 21007;
        Path destDir = tempDir.resolve("v4_retry_dest");
        Files.createDirectories(destDir);

        // Pre-stage an interrupted receiver session (512 KB transferred out of 1 MB)
        File partFile = destDir.resolve("protocol_test.bin.part").toFile();
        File metaFile = destDir.resolve("protocol_test.bin.part.meta").toFile();
        int halfSize = 512 * 1024;
        try (FileOutputStream fos = new FileOutputStream(partFile))
        {
            fos.write(sourceContent, 0, halfSize);
        }

        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(sourceContent);
        com.airsocket.transfer.TransferMetadata stageMeta = com.airsocket.transfer.TransferMetadata.create(
            "retry-test-id", "protocol_test.bin", sourceContent.length, checksum, Chunk.DEFAULT_CHUNK_SIZE,
            halfSize, false, null, null, null
        );
        stageMeta.save(metaFile.toPath());

        // First listener will immediately abort the first attempt after handshake
        AtomicInteger connectionCount = new AtomicInteger(0);
        ServerSocket serverSocket = new ServerSocket(serverPort);
        serverSocket.setReuseAddress(true);

        Thread proxyThread = Thread.ofVirtual().start(() ->
        {
            try
            {
                // Attempt 1: Accept and abruptly reset
                Socket s1 = serverSocket.accept();
                connectionCount.incrementAndGet();
                s1.close(); // Abrupt drop!

                // Attempt 2: Start a real Receiver to handle the retry!
                serverSocket.close();
            }
            catch (Exception ignored)
            {
            }
        });

        // Background thread to start real receiver once mock drops
        Thread receiverStarter = Thread.ofVirtual().start(() ->
        {
            try
            {
                while (!serverSocket.isClosed())
                {
                    Thread.sleep(50);
                }
                Receiver realReceiver = new Receiver(serverPort, false, "", true, destDir);
                startReceiver(realReceiver);
            }
            catch (Exception ignored)
            {
            }
        });

        // Sender will attempt connect, fail on first attempt, retry, resume from 512 KB, and finish!
        Sender.sendFile("127.0.0.1", serverPort, sourceFile, false, "", true, false,
            ProtocolVersion.V4, 3000, 5000, 3);

        File finalFile = destDir.resolve("protocol_test.bin").toFile();
        assertTrue(finalFile.exists(), "Final file must exist after retry completion");
        assertEquals(sourceContent.length, finalFile.length());
        assertArrayEquals(sourceContent, Files.readAllBytes(finalFile.toPath()));
        assertFalse(partFile.exists(), "Part file must be cleaned up");
        assertFalse(metaFile.exists(), "Meta file must be cleaned up");

        proxyThread.join(1000);
        receiverStarter.join(1000);
    }

    @Test
    public void testV4NonRetryableAuthErrorDoesNotLoop()
    {
        int port = 21008;
        Path destDir = tempDir.resolve("v4_no_retry_dest");
        assertDoesNotThrow(() ->
        {
            startReceiver(new Receiver(port, true, "secret", true, destDir));
        });

        long start = System.currentTimeMillis();
        assertThrows(AirSocketProtocolException.class, () ->
        {
            // Even though resume=true, AUTH_FAILED must terminate immediately without retry delays
            Sender.sendFile("127.0.0.1", port, sourceFile, true, "bad-pass", true, false,
                ProtocolVersion.V4, 3000, 5000, 3);
        });
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2000, "Non-retryable auth error must fail fast (< 2000ms), took: " + elapsed + "ms");
    }

    @Test
    public void testV4UnsupportedVersionSendsExplicitErrorFrame() throws Exception
    {
        int port = 21009;
        Path destDir = tempDir.resolve("v4_unsupported_ver_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            out.writeInt(0x41525354); // ARST magic
            out.writeByte(99); // Unsupported version 99
            out.flush();

            Frame reply = Frame.readFrom(in);
            assertEquals(FrameType.ERROR, reply.type());
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, reply.parseErrorCode());
        }
    }

    @Test
    public void testV4DoneFrameTransferIdMismatchSendsErrorFrame() throws Exception
    {
        int port = 21010;
        Path destDir = tempDir.resolve("v4_done_id_mismatch_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID sessionTransferId = UUID.randomUUID();
        UUID mismatchDoneTransferId = UUID.randomUUID();

        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            byte[] nameBytes = "done_mismatch.bin".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(nameBytes.length);
            dos.write(nameBytes);
            dos.writeLong(10L);
            dos.writeInt(32);
            dos.write(new byte[32]);
            dos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);

            Frame initFrame = Frame.handshakeInit(sessionTransferId, ProtocolVersion.V4, false, false, baos.toByteArray());
            initFrame.writeTo(out);

            Frame ack = Frame.readFrom(in);
            assertEquals(FrameType.HANDSHAKE_ACK, ack.type());

            // Send chunk with correct transfer ID
            Frame chunk = Frame.chunkData(sessionTransferId, 0L, new byte[10], true);
            chunk.writeTo(out);

            // Send DONE frame with MISMATCHED transfer ID
            Frame doneMismatched = Frame.transferDone(mismatchDoneTransferId, new byte[32]);
            doneMismatched.writeTo(out);

            Frame reply = Frame.readFrom(in);
            assertEquals(FrameType.ERROR, reply.type());
            assertEquals(ErrorCode.INVALID_TRANSFER_ID, reply.parseErrorCode());
        }
    }

    @Test
    public void testV4PingPongExchangeDuringTransfer() throws Exception
    {
        int port = 21011;
        Path destDir = tempDir.resolve("v4_ping_pong_dest");
        startReceiver(new Receiver(port, false, "", false, destDir));

        UUID transferId = UUID.randomUUID();
        try (Socket socket = new Socket("127.0.0.1", port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream()))
        {
            byte[] nameBytes = "ping_test.bin".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(nameBytes.length);
            dos.write(nameBytes);
            dos.writeLong(100L);
            dos.writeInt(0);
            dos.writeInt(Chunk.DEFAULT_CHUNK_SIZE);

            Frame initFrame = Frame.handshakeInit(transferId, ProtocolVersion.V4, false, false, baos.toByteArray());
            initFrame.writeTo(out);

            Frame ack = Frame.readFrom(in);
            assertEquals(FrameType.HANDSHAKE_ACK, ack.type());

            // Send PING frame
            Frame pingFrame = Frame.ping(transferId);
            pingFrame.writeTo(out);

            // Receiver must reply with PONG frame
            Frame pongReply = Frame.readFrom(in);
            assertEquals(FrameType.PONG, pongReply.type());
            assertEquals(transferId, pongReply.transferId());
        }
    }

    @Test
    public void testV4ReceiverConfigurableSocketTimeout()
    {
        try (Receiver r = new Receiver(21012, false, "", false))
        {
            assertEquals(15000, r.getSocketTimeoutMs());
            r.setSocketTimeoutMs(8000);
            assertEquals(8000, r.getSocketTimeoutMs());
        }
    }
}
