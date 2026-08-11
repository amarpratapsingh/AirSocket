package com.airsocket.transfer;

import com.airsocket.crypto.Crypto;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

public class Receiver
{
    private final int port;
    private final boolean encrypt;
    private final String passphrase;
    private final boolean resume;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public Receiver(int port, boolean encrypt, String passphrase, boolean resume)
    {
        this.port = port;
        this.encrypt = encrypt;
        this.passphrase = passphrase;
        this.resume = resume;
    }

    public void start() throws Exception
    {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        System.out.println("Listening for incoming transfers on port " + port + "...");

        while (running)
        {
            try
            {
                Socket socket = serverSocket.accept();
                new Thread(() ->
                {
                    try
                    {
                        handleConnection(socket);
                    }
                    catch (Exception e)
                    {
                        System.err.println("Error handling transfer: " + e.getMessage());
                    }
                }).start();
            }
            catch (SocketException e)
            {
                break;
            }
        }
    }

    public void stop()
    {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed())
        {
            try
            {
                serverSocket.close();
            }
            catch (IOException e)
            {
                // Ignore
            }
        }
    }

    private void handleConnection(Socket socket) throws Exception
    {
        try (InputStream in = socket.getInputStream();
             DataInputStream dataIn = new DataInputStream(in);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            int nameLength = dataIn.readInt();
            byte[] nameBytes = new byte[nameLength];
            dataIn.readFully(nameBytes);
            String fileName = new String(nameBytes, "UTF-8");
            long totalSize = dataIn.readLong();

            if ("__BENCHMARK__".equals(fileName))
            {
                dataOut.write(0x06);
                dataOut.flush();

                byte[] buffer = new byte[65536];
                long bytesRead = 0;
                while (bytesRead < totalSize)
                {
                    int toRead = (int) Math.min(buffer.length, totalSize - bytesRead);
                    int read = in.read(buffer, 0, toRead);
                    if (read == -1)
                    {
                        break;
                    }
                    bytesRead += read;
                }
                return;
            }

            File partFile = new File(fileName + ".part");
            File metaFile = new File(fileName + ".part.meta");
            File finalFile = new File(fileName);

            long offset = 0;
            boolean append = false;

            if (resume && partFile.exists() && metaFile.exists())
            {
                try (FileInputStream fis = new FileInputStream(metaFile))
                {
                    byte[] metaBytes = fis.readAllBytes();
                    offset = Long.parseLong(new String(metaBytes, "UTF-8").trim());
                    append = true;
                }
                catch (Exception e)
                {
                    offset = 0;
                    append = false;
                }
            }

            if (offset > 0)
            {
                String resumeMsg = "RESUME:" + offset + "\n";
                dataOut.write(resumeMsg.getBytes("UTF-8"));
            }
            else
            {
                dataOut.write(0x06);
            }
            dataOut.flush();

            InputStream targetIn = in;
            if (encrypt)
            {
                byte[] salt = new byte[16];
                byte[] iv = new byte[12];
                dataIn.readFully(salt);
                dataIn.readFully(iv);

                Cipher cipher = Crypto.getDecryptCipher(passphrase, salt, iv);
                targetIn = new CipherInputStream(in, cipher);
            }

            byte[] buffer = new byte[65536];
            long bytesReceived = offset;
            long expectedBytesToRead = totalSize - offset;
            long currentSessionBytes = 0;

            try (FileOutputStream fos = new FileOutputStream(partFile, append))
            {
                while (currentSessionBytes < expectedBytesToRead)
                {
                    int toRead = (int) Math.min(buffer.length, expectedBytesToRead - currentSessionBytes);
                    int read = targetIn.read(buffer, 0, toRead);
                    if (read == -1)
                    {
                        throw new IOException("Stream ended prematurely");
                    }
                    fos.write(buffer, 0, read);
                    bytesReceived += read;
                    currentSessionBytes += read;

                    try (FileOutputStream metaOut = new FileOutputStream(metaFile))
                    {
                        metaOut.write(String.valueOf(bytesReceived).getBytes("UTF-8"));
                    }
                }
            }

            if (bytesReceived == totalSize)
            {
                if (finalFile.exists())
                {
                    finalFile.delete();
                }
                if (partFile.renameTo(finalFile))
                {
                    metaFile.delete();
                }
                else
                {
                    throw new IOException("Failed to rename temporary file");
                }
            }
            else
            {
                throw new IOException("Transfer interrupted");
            }
        }
        finally
        {
            socket.close();
        }
    }
}
