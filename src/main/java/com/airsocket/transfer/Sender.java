package com.airsocket.transfer;

import com.airsocket.crypto.Crypto;
import com.airsocket.protocol.AirSocketProtocolException;
import com.airsocket.protocol.ErrorCode;
import com.airsocket.protocol.Frame;
import com.airsocket.protocol.FrameType;
import com.airsocket.protocol.ProtocolVersion;
import com.airsocket.protocol.TransferState;
import com.airsocket.protocol.TransferStateMachine;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Sender
{
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final int DEFAULT_INITIAL_BACKOFF_MS = 200;

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
        sendFile(host, port, file, encrypt, passphrase, resume, progress, ProtocolVersion.CURRENT);
    }

    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        String passphrase,
        boolean resume,
        boolean progress,
        ProtocolVersion version
    ) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            sendFile(host, port, file, encrypt, chars, resume, progress, version);
        }
        finally
        {
            Crypto.wipe(chars);
        }
    }

    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        char[] passphrase,
        boolean resume,
        boolean progress
    ) throws Exception
    {
        sendFile(host, port, file, encrypt, passphrase, resume, progress, ProtocolVersion.CURRENT);
    }

    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        char[] passphrase,
        boolean resume,
        boolean progress,
        ProtocolVersion version
    ) throws Exception
    {
        sendFile(
            host,
            port,
            file,
            encrypt,
            passphrase,
            resume,
            progress,
            version,
            DEFAULT_CONNECT_TIMEOUT_MS,
            DEFAULT_READ_TIMEOUT_MS,
            resume ? DEFAULT_MAX_RETRIES : 0
        );
    }

    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        String passphrase,
        boolean resume,
        boolean progress,
        ProtocolVersion version,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRetries
    ) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            sendFile(host, port, file, encrypt, chars, resume, progress, version, connectTimeoutMs, readTimeoutMs, maxRetries);
        }
        finally
        {
            Crypto.wipe(chars);
        }
    }

    public static void sendFile(
        String host,
        int port,
        File file,
        boolean encrypt,
        char[] passphrase,
        boolean resume,
        boolean progress,
        ProtocolVersion version,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRetries
    ) throws Exception
    {
        if (!file.exists() || !file.isFile())
        {
            throw new IllegalArgumentException("Target is not a valid file");
        }

        long fileSize = file.length();
        String fileName = file.getName();
        byte[] checksum = computeSha256(file);
        UUID transferId = UUID.randomUUID();

        System.out.printf("[%s] Initiating transfer for: %s (%d bytes, version: %s)%n",
            transferId, fileName, fileSize, version);

        if (version == ProtocolVersion.V4)
        {
            int attempt = 0;
            while (true)
            {
                try
                {
                    attemptSendFileV4(
                        host, port, file, fileSize, fileName, checksum, encrypt, passphrase,
                        resume, progress, transferId, connectTimeoutMs, readTimeoutMs
                    );
                    break;
                }
                catch (AirSocketProtocolException e)
                {
                    if (!isRetryable(e.getErrorCode()) || attempt >= maxRetries)
                    {
                        throw e;
                    }
                    attempt++;
                    long backoff = (long) DEFAULT_INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    System.out.printf("[%s] Protocol error: %s. Retrying (%d/%d) in %d ms...%n",
                        transferId, e.getMessage(), attempt, maxRetries, backoff);
                    Thread.sleep(backoff);
                }
                catch (IOException e)
                {
                    if (attempt >= maxRetries)
                    {
                        throw e;
                    }
                    attempt++;
                    long backoff = (long) DEFAULT_INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    System.out.printf("[%s] Connection error: %s. Retrying (%d/%d) in %d ms...%n",
                        transferId, e.getMessage(), attempt, maxRetries, backoff);
                    Thread.sleep(backoff);
                }
            }
        }
        else
        {
            attemptSendFileLegacy(
                host, port, file, fileSize, fileName, checksum, encrypt, passphrase,
                resume, progress, transferId.toString(), version, connectTimeoutMs, readTimeoutMs
            );
        }
    }

    private static boolean isRetryable(ErrorCode code)
    {
        return switch (code)
        {
            case AUTH_FAILED, UNSUPPORTED_VERSION, INSUFFICIENT_SPACE,
                 CHECKSUM_MISMATCH, PATH_TRAVERSAL, TRANSFER_REJECTED,
                 INVALID_TRANSFER_ID -> false;
            default -> true;
        };
    }

    private static void attemptSendFileV4(
        String host,
        int port,
        File file,
        long fileSize,
        String fileName,
        byte[] checksum,
        boolean encrypt,
        char[] passphrase,
        boolean resume,
        boolean progress,
        UUID transferId,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws Exception
    {
        int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;

        try (SocketChannel socketChannel = SocketChannel.open();
             FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ))
        {
            Socket socket = socketChannel.socket();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(256 * 1024);
            socket.setSendBufferSize(256 * 1024);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

            OutputStream out = socket.getOutputStream();
            DataOutputStream dataOut = new DataOutputStream(out);
            InputStream in = socket.getInputStream();
            DataInputStream dataIn = new DataInputStream(in);

            TransferStateMachine stateMachine = new TransferStateMachine(transferId);
            stateMachine.transition(TransferState.HANDSHAKING);

            SecretKeySpec secretKey = null;
            byte[] ivData = null;

            byte[] initPayload;
            if (encrypt)
            {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[Crypto.SALT_LENGTH];
                byte[] ivMeta = new byte[Crypto.IV_LENGTH];
                ivData = new byte[Crypto.IV_LENGTH];
                random.nextBytes(salt);
                random.nextBytes(ivMeta);
                random.nextBytes(ivData);

                secretKey = Crypto.deriveKey(passphrase, salt);

                ByteArrayOutputStream metaBaos = new ByteArrayOutputStream();
                try (DataOutputStream metaDos = new DataOutputStream(metaBaos))
                {
                    metaDos.writeInt(0x41555448); // "AUTH"
                    byte[] tokenBytes = "AirSocket-V4-Token".getBytes(StandardCharsets.UTF_8);
                    metaDos.writeInt(tokenBytes.length);
                    metaDos.write(tokenBytes);

                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    metaDos.writeInt(nameBytes.length);
                    metaDos.write(nameBytes);
                    metaDos.writeLong(fileSize);
                    metaDos.writeInt(checksum.length);
                    metaDos.write(checksum);
                    metaDos.writeInt(chunkSize);
                }

                Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, Cipher.ENCRYPT_MODE);
                byte[] encryptedMeta = metaCipher.doFinal(metaBaos.toByteArray());

                ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
                try (DataOutputStream payloadDos = new DataOutputStream(payloadBaos))
                {
                    payloadDos.write(salt);
                    payloadDos.write(ivMeta);
                    payloadDos.write(ivData);
                    payloadDos.writeInt(encryptedMeta.length);
                    payloadDos.write(encryptedMeta);
                }
                initPayload = payloadBaos.toByteArray();
            }
            else
            {
                ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
                try (DataOutputStream payloadDos = new DataOutputStream(payloadBaos))
                {
                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    payloadDos.writeInt(nameBytes.length);
                    payloadDos.write(nameBytes);
                    payloadDos.writeLong(fileSize);
                    payloadDos.writeInt(checksum.length);
                    payloadDos.write(checksum);
                    payloadDos.writeInt(chunkSize);
                }
                initPayload = payloadBaos.toByteArray();
            }

            Frame initFrame = Frame.handshakeInit(transferId, ProtocolVersion.V4, encrypt, resume, initPayload);
            initFrame.writeTo(dataOut);

            Frame responseFrame = Frame.readFrom(dataIn);
            if (responseFrame.type() == FrameType.ERROR)
            {
                ErrorCode err = responseFrame.parseErrorCode();
                String msg = responseFrame.parseErrorMessage();
                throw new AirSocketProtocolException(err, responseFrame.transferId(), msg);
            }

            if (!responseFrame.transferId().equals(transferId))
            {
                throw new AirSocketProtocolException(
                    ErrorCode.INVALID_TRANSFER_ID,
                    transferId,
                    "Transfer ID mismatch in handshake response: expected " + transferId + " but received " + responseFrame.transferId()
                );
            }

            ProtocolVersion negotiatedVersion = responseFrame.parseNegotiatedVersion();
            if (negotiatedVersion == null || !ProtocolVersion.isSupported(negotiatedVersion.versionNumber()))
            {
                throw new AirSocketProtocolException(
                    ErrorCode.UNSUPPORTED_VERSION,
                    transferId,
                    "Unsupported negotiated protocol version: " + negotiatedVersion
                );
            }

            stateMachine.validateIncomingFrame(responseFrame.type());
            stateMachine.transition(TransferState.READY);

            long offset = resume ? responseFrame.parseResumeOffset() : 0L;
            long bytesSent = offset;
            long chunkIndex = offset / chunkSize;
            long startTime = System.currentTimeMillis();
            long lastPrintTime = startTime;

            stateMachine.transition(TransferState.TRANSFERRING);

            java.nio.ByteBuffer fileBuffer = java.nio.ByteBuffer.allocate(chunkSize);
            fileChannel.position(offset);

            while (bytesSent < fileSize)
            {
                int toRead = (int) Math.min((long) chunkSize, fileSize - bytesSent);
                fileBuffer.clear();
                fileBuffer.limit(toRead);
                while (fileBuffer.hasRemaining())
                {
                    int r = fileChannel.read(fileBuffer);
                    if (r == -1)
                    {
                        throw new IOException("Premature EOF reading source file at position " + bytesSent);
                    }
                }
                fileBuffer.flip();

                byte[] plainBytes = Arrays.copyOf(fileBuffer.array(), fileBuffer.limit());
                boolean isLast = (bytesSent + plainBytes.length == fileSize);

                if (encrypt)
                {
                    byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                    Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.ENCRYPT_MODE);
                    byte[] encryptedChunk = chunkCipher.doFinal(plainBytes);

                    Frame chunkFrame = Frame.chunkData(transferId, chunkIndex, encryptedChunk, isLast);
                    chunkFrame.writeTo(dataOut);
                }
                else
                {
                    Frame chunkFrame = Frame.chunkData(transferId, chunkIndex, plainBytes, isLast);
                    chunkFrame.writeTo(dataOut);
                }

                bytesSent += plainBytes.length;
                chunkIndex++;

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

            stateMachine.transition(TransferState.FINALIZING);
            Frame doneFrame = Frame.transferDone(transferId, checksum);
            doneFrame.writeTo(dataOut);

            Frame ackFrame = Frame.readFrom(dataIn);
            if (ackFrame.type() == FrameType.ERROR)
            {
                ErrorCode err = ackFrame.parseErrorCode();
                String msg = ackFrame.parseErrorMessage();
                throw new AirSocketProtocolException(err, ackFrame.transferId(), msg);
            }
            if (!ackFrame.transferId().equals(transferId))
            {
                throw new AirSocketProtocolException(
                    ErrorCode.INVALID_TRANSFER_ID,
                    transferId,
                    "Transfer ID mismatch in transfer acknowledgement: expected " + transferId + " but received " + ackFrame.transferId()
                );
            }
            stateMachine.validateIncomingFrame(ackFrame.type());
            stateMachine.transition(TransferState.COMPLETED);
            stateMachine.transition(TransferState.CLOSED);

            long elapsedMs = Math.max(1L, System.currentTimeMillis() - startTime);
            double throughputMbps = ((bytesSent - offset) * 8.0) / (elapsedMs * 1000.0);
            if (progress)
            {
                System.out.println();
            }
            System.out.printf("[%s] Transfer complete: %s (%d bytes, %.2f Mbps, %d ms)%n",
                transferId,
                fileName,
                bytesSent - offset,
                throughputMbps,
                elapsedMs
            );
        }
        catch (Exception e)
        {
            throw e;
        }
    }

    private static void attemptSendFileLegacy(
        String host,
        int port,
        File file,
        long fileSize,
        String fileName,
        byte[] checksum,
        boolean encrypt,
        char[] passphrase,
        boolean resume,
        boolean progress,
        String transferId,
        ProtocolVersion version,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws Exception
    {
        SecretKeySpec secretKey = null;
        byte[] ivData = null;

        try (SocketChannel socketChannel = SocketChannel.open();
             FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ))
        {
            Socket socket = socketChannel.socket();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(256 * 1024);
            socket.setSendBufferSize(256 * 1024);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

            OutputStream out = socket.getOutputStream();
            DataOutputStream dataOut = new DataOutputStream(out);
            InputStream in = socket.getInputStream();
            DataInputStream dataIn = new DataInputStream(in);

            // Handshake with a versioned metadata frame and checksum
            dataOut.writeInt(0x41525354);

            if (encrypt || version == ProtocolVersion.V2)
            {
                dataOut.writeByte(2);

                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[Crypto.SALT_LENGTH];
                byte[] ivMeta = new byte[Crypto.IV_LENGTH];
                ivData = new byte[Crypto.IV_LENGTH];
                random.nextBytes(salt);
                random.nextBytes(ivMeta);
                random.nextBytes(ivData);

                secretKey = Crypto.deriveKey(passphrase, salt);

                ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
                try (DataOutputStream metaDataOut = new DataOutputStream(metaOut))
                {
                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    metaDataOut.writeInt(nameBytes.length);
                    metaDataOut.write(nameBytes);
                    metaDataOut.writeLong(fileSize);
                    metaDataOut.writeInt(checksum.length);
                    metaDataOut.write(checksum);

                    byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
                    metaDataOut.writeInt(idBytes.length);
                    metaDataOut.write(idBytes);

                    metaDataOut.writeInt(Chunk.DEFAULT_CHUNK_SIZE);
                }
                byte[] rawMeta = metaOut.toByteArray();

                Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, Cipher.ENCRYPT_MODE);
                byte[] encryptedMeta = metaCipher.doFinal(rawMeta);

                dataOut.write(salt);
                dataOut.write(ivMeta);
                dataOut.write(ivData);
                dataOut.writeInt(encryptedMeta.length);
                dataOut.write(encryptedMeta);
                dataOut.flush();
            }
            else
            {
                dataOut.writeByte(version == ProtocolVersion.V1 ? 1 : 3);
                byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                dataOut.writeInt(nameBytes.length);
                dataOut.write(nameBytes);
                dataOut.writeLong(fileSize);
                dataOut.writeInt(checksum.length);
                dataOut.write(checksum);

                if (version != ProtocolVersion.V1)
                {
                    byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
                    dataOut.writeInt(idBytes.length);
                    dataOut.write(idBytes);
                }
                dataOut.flush();
            }

            // Read ACK or RESUME
            long offset = 0;
            if (resume)
            {
                int firstByte = dataIn.read();
                if (firstByte == 0x06)
                {
                    offset = 0;
                }
                else if (firstByte == 'R')
                {
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
                else
                {
                    throw new IOException("Failed handshake or rejected by receiver (response: " + firstByte + ")");
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

            long bytesSent = offset;
            long startTime = System.currentTimeMillis();
            long lastPrintTime = startTime;

            if (encrypt)
            {
                int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;
                long chunkIndex = offset / chunkSize;
                java.nio.ByteBuffer fileBuffer = java.nio.ByteBuffer.allocate(chunkSize);
                fileChannel.position(offset);

                while (bytesSent < fileSize)
                {
                    int toRead = (int) Math.min((long) chunkSize, fileSize - bytesSent);
                    fileBuffer.clear();
                    fileBuffer.limit(toRead);
                    while (fileBuffer.hasRemaining())
                    {
                        int r = fileChannel.read(fileBuffer);
                        if (r == -1)
                        {
                            throw new IOException("Premature EOF reading source file at position " + bytesSent);
                        }
                    }
                    fileBuffer.flip();

                    byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                    Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.ENCRYPT_MODE);
                    byte[] encryptedChunk = chunkCipher.doFinal(fileBuffer.array(), 0, fileBuffer.limit());

                    dataOut.writeInt(encryptedChunk.length);
                    dataOut.write(encryptedChunk);
                    dataOut.flush();

                    bytesSent += toRead;
                    chunkIndex++;

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
            }
            else
            {
                dataOut.flush();
                long position = offset;
                long remaining = fileSize - offset;

                while (remaining > 0)
                {
                    long chunkSize = progress ? Math.min(remaining, 8L * 1024 * 1024) : Math.min(remaining, 32L * 1024 * 1024);
                    long transferred = fileChannel.transferTo(position, chunkSize, socketChannel);
                    if (transferred <= 0)
                    {
                        throw new IOException("Zero-copy transfer stalled: SocketChannel transferred 0 bytes");
                    }
                    position += transferred;
                    remaining -= transferred;
                    bytesSent = position;

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
            }

            long elapsedMs = Math.max(1L, System.currentTimeMillis() - startTime);
            double throughputMbps = ((bytesSent - offset) * 8.0) / (elapsedMs * 1000.0);
            if (progress)
            {
                System.out.println();
            }
            System.out.printf("[%s] Transfer complete: %s (%d bytes, %.2f Mbps, %d ms)%n",
                transferId,
                fileName,
                bytesSent - offset,
                throughputMbps,
                elapsedMs
            );
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
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(bufSize);
                try (OutputStream out = socket.getOutputStream();
                     DataOutputStream dataOut = new DataOutputStream(out))
                {
                    byte[] nameBytes = "__BENCHMARK__".getBytes("UTF-8");
                    dataOut.writeInt(nameBytes.length);
                    dataOut.write(nameBytes);
                    long totalSize = 100L * 1024 * 1024;
                    dataOut.writeLong(totalSize);
                    dataOut.flush();

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
            Thread.sleep(200);
        }
    }

    private static byte[] computeSha256(File file) throws IOException
    {
        try (InputStream in = new FileInputStream(file))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        }
        catch (Exception e)
        {
            throw new IOException("Failed to compute file checksum", e);
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
