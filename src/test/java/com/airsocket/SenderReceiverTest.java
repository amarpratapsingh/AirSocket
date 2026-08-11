package com.airsocket;

import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
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
        // Clean up temporary files in user working directory if any were created during tests
        new File("source.bin").delete();
        new File("source.part").delete();
        new File("source.part.meta").delete();
    }

    @Test
    public void testStandardTransfer() throws Exception
    {
        receiver = new Receiver(receiverPort, false, "", false);
        receiverThread = new Thread(() ->
        {
            try
            {
                receiver.start();
            }
            catch (Exception e)
            {
                // Ignore
            }
        });
        receiverThread.start();
        Thread.sleep(100);

        Sender.sendFile("127.0.0.1", receiverPort, sourceFile, false, "", false, false);

        // Allow file rename and cleanup to complete
        Thread.sleep(200);

        File receivedFile = new File("source.bin");
        assertTrue(receivedFile.exists(), "Received file should exist");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should be identical");
    }

    @Test
    public void testEncryptedTransfer() throws Exception
    {
        String passphrase = "test-passphrase";
        receiver = new Receiver(receiverPort, true, passphrase, false);
        receiverThread = new Thread(() ->
        {
            try
            {
                receiver.start();
            }
            catch (Exception e)
            {
                // Ignore
            }
        });
        receiverThread.start();
        Thread.sleep(100);

        Sender.sendFile("127.0.0.1", receiverPort, sourceFile, true, passphrase, false, false);

        Thread.sleep(200);

        File receivedFile = new File("source.bin");
        assertTrue(receivedFile.exists(), "Received file should exist");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Contents should be identical");
    }

    @Test
    public void testResumeTransfer() throws Exception
    {
        // 1. Manually prepare a partial download state on the receiver side
        File partFile = new File("source.bin.part");
        File metaFile = new File("source.bin.part.meta");

        int offset = 1 * 1024 * 1024; // 1MB already received
        byte[] partialContent = Arrays.copyOfRange(sourceContent, 0, offset);

        try (FileOutputStream fos = new FileOutputStream(partFile))
        {
            fos.write(partialContent);
        }

        try (FileOutputStream fos = new FileOutputStream(metaFile))
        {
            fos.write(String.valueOf(offset).getBytes("UTF-8"));
        }

        // 2. Start receiver with resume enabled
        receiver = new Receiver(receiverPort, false, "", true);
        receiverThread = new Thread(() ->
        {
            try
            {
                receiver.start();
            }
            catch (Exception e)
            {
                // Ignore
            }
        });
        receiverThread.start();
        Thread.sleep(100);

        // 3. Send file with resume enabled
        Sender.sendFile("127.0.0.1", receiverPort, sourceFile, false, "", true, false);

        Thread.sleep(200);

        File receivedFile = new File("source.bin");
        assertTrue(receivedFile.exists(), "Received file should exist after resume completion");
        assertEquals(sourceFile.length(), receivedFile.length(), "Sizes should match");

        byte[] receivedContent = Files.readAllBytes(receivedFile.toPath());
        assertTrue(Arrays.equals(sourceContent, receivedContent), "Full contents should be identical");
    }
}
