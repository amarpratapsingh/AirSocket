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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MultiClientConcurrentIntegrationTest
{
    @TempDir
    public Path tempDir;

    private Receiver receiver;
    private Thread receiverThread;
    private final int port = 22001;

    @BeforeEach
    public void setUp() throws Exception
    {
        Path downloadDir = tempDir.resolve("concurrent_downloads");
        receiver = new Receiver(port, false, "", false, downloadDir);
        receiverThread = Thread.ofVirtual().name("concurrent-receiver").start(() ->
        {
            try
            {
                receiver.start();
            }
            catch (Exception ignored)
            {
            }
        });
        assertTrue(receiver.awaitReady(5, TimeUnit.SECONDS), "Receiver must start within 5s");
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

    @Test
    public void testMultipleConcurrentTransfersCompleteSuccessfully() throws Exception
    {
        int clientCount = 4;
        int fileSizeBytes = 256 * 1024; // 256 KB each
        SecureRandom random = new SecureRandom();

        List<File> sourceFiles = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();

        for (int i = 0; i < clientCount; i++)
        {
            byte[] data = new byte[fileSizeBytes];
            random.nextBytes(data);
            contents.add(data);

            File f = tempDir.resolve("client_file_" + i + ".bin").toFile();
            try (FileOutputStream fos = new FileOutputStream(f))
            {
                fos.write(data);
            }
            sourceFiles.add(f);
        }

        try (ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor())
        {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < clientCount; i++)
            {
                final File fileToSend = sourceFiles.get(i);
                tasks.add(() ->
                {
                    Sender.sendFile("127.0.0.1", port, fileToSend, false, "", false, false);
                    return null;
                });
            }

            List<Future<Void>> futures = clientExecutor.invokeAll(tasks);
            for (Future<Void> future : futures)
            {
                assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS),
                    "Each concurrent transfer must finish successfully");
            }
        }

        Path downloadDir = tempDir.resolve("concurrent_downloads");
        for (int i = 0; i < clientCount; i++)
        {
            File receivedFile = downloadDir.resolve("client_file_" + i + ".bin").toFile();
            assertTrue(receivedFile.exists(), "Received file " + i + " must exist");
            assertEquals(fileSizeBytes, receivedFile.length());
            assertArrayEquals(contents.get(i), Files.readAllBytes(receivedFile.toPath()),
                "Received content for file " + i + " must match original byte-for-byte");
        }
    }
}
