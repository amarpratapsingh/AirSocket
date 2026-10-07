package com.airsocket.benchmark;

import com.airsocket.crypto.Crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BenchmarkServer implements Closeable
{
    private final int port;
    private final int defaultReceiveBufferSize;
    private final Path storageDir;
    private final String defaultPassphrase;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean running = true;

    public BenchmarkServer(int port) throws IOException
    {
        this(port, 0, null, "bench-passphrase");
    }

    public BenchmarkServer(int port, int defaultReceiveBufferSize, Path storageDir, String defaultPassphrase) throws IOException
    {
        this.port = port;
        this.defaultReceiveBufferSize = defaultReceiveBufferSize;
        this.storageDir = storageDir;
        this.defaultPassphrase = defaultPassphrase != null ? defaultPassphrase : "bench-passphrase";

        this.serverSocket = new ServerSocket();
        if (defaultReceiveBufferSize > 0)
        {
            this.serverSocket.setReceiveBufferSize(defaultReceiveBufferSize);
        }
        this.serverSocket.bind(new java.net.InetSocketAddress(port));
    }

    public void start()
    {
        executor.submit(() ->
        {
            readyLatch.countDown();
            while (running && !serverSocket.isClosed())
            {
                try
                {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                }
                catch (IOException e)
                {
                    break;
                }
            }
        });
    }

    public void awaitReady() throws InterruptedException
    {
        readyLatch.await();
    }

    public int getDefaultReceiveBufferSize()
    {
        return defaultReceiveBufferSize;
    }

    private void handleClient(Socket socket)
    {
        try (socket;
             InputStream in = socket.getInputStream();
             DataInputStream dataIn = new DataInputStream(in);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            int firstInt = dataIn.readInt();
            long totalBytes;
            int appBufSize = 64 * 1024;
            boolean encrypted = false;
            boolean saveToDisk = false;
            SecretKeySpec secretKey = null;
            byte[] ivData = null;

            if (firstInt == 0x41525354) // Extended benchmark protocol: ARST
            {
                int messageType = dataIn.readUnsignedByte();
                if (messageType != 0xBE)
                {
                    throw new IOException("Unsupported benchmark message type: " + messageType);
                }

                int flags = dataIn.readUnsignedByte();
                encrypted = (flags & 0x01) != 0;
                saveToDisk = (flags & 0x02) != 0;
                boolean tcpNoDelay = (flags & 0x04) != 0;
                socket.setTcpNoDelay(tcpNoDelay);

                totalBytes = dataIn.readLong();
                appBufSize = dataIn.readInt();
                int rcvBufSize = dataIn.readInt();
                if (rcvBufSize > 0)
                {
                    socket.setReceiveBufferSize(rcvBufSize);
                }

                if (encrypted)
                {
                    byte[] salt = new byte[Crypto.SALT_LENGTH];
                    byte[] ivMeta = new byte[Crypto.IV_LENGTH];
                    ivData = new byte[Crypto.IV_LENGTH];
                    dataIn.readFully(salt);
                    dataIn.readFully(ivMeta);
                    dataIn.readFully(ivData);

                    secretKey = Crypto.deriveKey(defaultPassphrase.toCharArray(), salt);
                }
            }
            else
            {
                // Legacy "__BENCHMARK__" handshake
                int nameLength = firstInt;
                if (nameLength <= 0 || nameLength > 255)
                {
                    throw new IOException("Invalid legacy benchmark handshake name length: " + nameLength);
                }
                byte[] nameBytes = new byte[nameLength];
                dataIn.readFully(nameBytes);
                totalBytes = dataIn.readLong();
            }

            // Send handshake ACK
            dataOut.write(0x06);
            dataOut.flush();

            // Receive payload
            File diskTarget = null;
            FileOutputStream fos = null;
            if (saveToDisk)
            {
                Path targetDir = storageDir != null ? storageDir : Path.of(System.getProperty("java.io.tmpdir"));
                Files.createDirectories(targetDir);
                diskTarget = targetDir.resolve("bench_received_" + System.currentTimeMillis() + ".tmp").toFile();
                fos = new FileOutputStream(diskTarget);
            }

            try
            {
                if (encrypted && secretKey != null && ivData != null)
                {
                    long bytesReceived = 0;
                    long chunkIndex = 0;

                    while (bytesReceived < totalBytes)
                    {
                        int incomingCipherLen = dataIn.readInt();
                        byte[] cipherBuf = new byte[incomingCipherLen];
                        dataIn.readFully(cipherBuf);

                        byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                        Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.DECRYPT_MODE);
                        byte[] plainChunk = chunkCipher.doFinal(cipherBuf);

                        if (fos != null)
                        {
                            fos.write(plainChunk);
                        }

                        bytesReceived += plainChunk.length;
                        chunkIndex++;
                    }
                }
                else
                {
                    byte[] buffer = new byte[Math.max(1024, appBufSize)];
                    long bytesReceived = 0;
                    while (bytesReceived < totalBytes)
                    {
                        int toRead = (int) Math.min(buffer.length, totalBytes - bytesReceived);
                        int read = in.read(buffer, 0, toRead);
                        if (read == -1)
                        {
                            break;
                        }
                        if (fos != null)
                        {
                            fos.write(buffer, 0, read);
                        }
                        bytesReceived += read;
                    }
                }

                if (fos != null)
                {
                    fos.flush();
                }

                // Send completion ACK
                dataOut.write(0x06);
                dataOut.flush();
            }
            finally
            {
                if (fos != null)
                {
                    fos.close();
                    if (diskTarget != null && diskTarget.exists())
                    {
                        diskTarget.delete();
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Client closed or error
        }
    }

    public int getPort()
    {
        return port;
    }

    @Override
    public void close()
    {
        running = false;
        try
        {
            serverSocket.close();
        }
        catch (IOException ignored) {}
        executor.shutdownNow();
    }
}
