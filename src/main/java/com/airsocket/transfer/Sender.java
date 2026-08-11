package com.airsocket.transfer;

import com.airsocket.crypto.Crypto;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;

public class Sender
{
    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        String passphrase,
        boolean resume,
        boolean progress
    ) throws Exception
    {
        if (!file.exists() || !file.isFile())
        {
            throw new IllegalArgumentException("Target is not a valid file");
        }

        long fileSize = file.length();
        String fileName = file.getName();

        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out);
             InputStream in = socket.getInputStream();
             DataInputStream dataIn = new DataInputStream(in);
             FileInputStream fileIn = new FileInputStream(file))
        {
            // 1. Handshake
            byte[] nameBytes = fileName.getBytes("UTF-8");
            dataOut.writeInt(nameBytes.length);
            dataOut.write(nameBytes);
            dataOut.writeLong(fileSize);
            dataOut.flush();

            // 2. Read ACK or RESUME
            long offset = 0;
            if (resume)
            {
                // Read response (could be 0x06 or "RESUME:<offset>\n")
                int firstByte = dataIn.read();
                if (firstByte == 0x06)
                {
                    offset = 0;
                }
                else if (firstByte == 'R')
                {
                    // Read until newline
                    StringBuilder sb = new StringBuilder();
                    sb.append((char) firstByte);
                    int b;
                    while ((b = dataIn.read()) != -1 && b != '\n')
                    {
                        sb.append((char) b);
                    }
                    String resp = sb.toString();
                    if (resp.startsWith("RESUME:"))
                    {
                        offset = Long.parseLong(resp.substring(7).trim());
                    }
                }
            }
            else
            {
                int ack = dataIn.read();
                if (ack != 0x06)
                {
                    throw new IOException("Failed handshake, receiver did not ACK");
                }
            }

            // 3. Skip to offset
            if (offset > 0)
            {
                long skipped = fileIn.skip(offset);
                if (skipped != offset)
                {
                    throw new IOException("Failed to skip to required offset: " + offset);
                }
                System.out.println("Resuming transfer from offset: " + offset);
            }

            // 4. Wrap with encryption if enabled
            OutputStream targetOut = out;
            if (encrypt)
            {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[16];
                byte[] iv = new byte[12];
                random.nextBytes(salt);
                random.nextBytes(iv);

                // Write salt + IV as plaintext
                dataOut.write(salt);
                dataOut.write(iv);
                dataOut.flush();

                Cipher cipher = Crypto.getEncryptCipher(passphrase, salt, iv);
                targetOut = new CipherOutputStream(out, cipher);
            }

            // 5. Transfer loop
            byte[] buffer = new byte[65536];
            long bytesSent = offset;
            int read;
            long startTime = System.currentTimeMillis();
            long lastPrintTime = startTime;

            while ((read = fileIn.read(buffer)) != -1)
            {
                targetOut.write(buffer, 0, read);
                bytesSent += read;

                if (progress)
                {
                    long now = System.currentTimeMillis();
                    if (now - lastPrintTime >= 100 || bytesSent == fileSize)
                    {
                        printProgress(bytesSent, fileSize, startTime, now);
                        lastPrintTime = now;
                    }
                }
            }

            targetOut.flush();
            if (encrypt)
            {
                // CipherOutputStream must be closed to write padding/tag (though GCM/NoPadding tag is written on close)
                targetOut.close();
            }
        }
    }

    public static void runBenchmark(String host, int port, int[] bufferSizes) throws Exception
    {
        System.out.println(String.format("Benchmarking TCP throughput to %s:%d...", host, port));
        byte[] dummyData = new byte[65536];
        new SecureRandom().nextBytes(dummyData);

        for (int bufSize : bufferSizes)
        {
            try (Socket socket = new Socket(host, port))
            {
                socket.setSendBufferSize(bufSize);
                try (OutputStream out = socket.getOutputStream();
                     DataOutputStream dataOut = new DataOutputStream(out))
                {
                    // Handshake for benchmark
                    byte[] nameBytes = "__BENCHMARK__".getBytes("UTF-8");
                    dataOut.writeInt(nameBytes.length);
                    dataOut.write(nameBytes);
                    // 100MB payload size
                    long totalSize = 100L * 1024 * 1024;
                    dataOut.writeLong(totalSize);
                    dataOut.flush();

                    // Read ACK
                    InputStream in = socket.getInputStream();
                    int ack = in.read();
                    if (ack != 0x06)
                    {
                        System.out.println("Failed benchmark handshake");
                        continue;
                    }

                    long bytesSent = 0;
                    long startTime = System.nanoTime();
                    while (bytesSent < totalSize)
                    {
                        int toWrite = (int) Math.min(dummyData.length, totalSize - bytesSent);
                        out.write(dummyData, 0, toWrite);
                        bytesSent += toWrite;
                    }
                    out.flush();
                    long endTime = System.nanoTime();

                    double seconds = (endTime - startTime) / 1_000_000_000.0;
                    double mbps = (totalSize * 8) / (seconds * 1_000_000.0);

                    System.out.println(String.format("  Buffer: %3d KB    %.1f Mbps", bufSize / 1024, mbps));
                }
            }
            // Add a small pause between benchmarks
            Thread.sleep(200);
        }
    }

    private static void printProgress(long currentBytes, long totalBytes, long startTime, long now)
    {
        int width = 30;
        double percent = totalBytes > 0 ? (double) currentBytes / totalBytes : 1.0;
        int filled = (int) (percent * width);

        StringBuilder sb = new StringBuilder("\r");
        for (int i = 0; i < width; i++)
        {
            if (i < filled)
            {
                sb.append("█");
            }
            else
            {
                sb.append("░");
            }
        }

        double speedMBs = 0.0;
        long duration = now - startTime;
        if (duration > 0)
        {
            speedMBs = ((double) (currentBytes) / (1024 * 1024)) / ((double) duration / 1000);
        }

        sb.append(String.format("  %3d%%  (%d/%d MB)  %.1f MB/s",
            (int) (percent * 100),
            currentBytes / (1024 * 1024),
            totalBytes / (1024 * 1024),
            speedMBs
        ));

        System.out.print(sb.toString());
        if (currentBytes == totalBytes)
        {
            System.out.println();
        }
    }
}
