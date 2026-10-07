package com.airsocket.transfer;

import com.airsocket.crypto.Crypto;
import com.airsocket.protocol.AirSocketProtocolException;
import com.airsocket.protocol.ErrorCode;
import com.airsocket.protocol.Frame;
import com.airsocket.protocol.FrameType;
import com.airsocket.protocol.ProtocolVersion;
import com.airsocket.protocol.TransferState;
import com.airsocket.protocol.TransferStateMachine;
import java.io.ByteArrayInputStream;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.SecretKeySpec;

public class Receiver implements AutoCloseable
{
    @FunctionalInterface
    public interface DiskSpaceValidator
    {
        boolean hasSpace(Path dir, long requiredBytes);
    }

    private static final long META_FLUSH_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
    private static final long META_FLUSH_BYTES = 16L * 1024 * 1024;

    private final int port;
    private final boolean encrypt;
    private final char[] passphrase;
    private final boolean resume;
    private final Path downloadDir;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private int socketTimeoutMs = 15000;
    private DiskSpaceValidator diskSpaceValidator = (dir, required) ->
    {
        long usable = dir.toFile().getUsableSpace();
        return usable <= 0 || usable >= required;
    };

    public void setSocketTimeoutMs(int socketTimeoutMs)
    {
        this.socketTimeoutMs = socketTimeoutMs > 0 ? socketTimeoutMs : 15000;
    }

    public int getSocketTimeoutMs()
    {
        return this.socketTimeoutMs;
    }

    public void setDiskSpaceValidator(DiskSpaceValidator validator)
    {
        this.diskSpaceValidator = validator != null ? validator : (dir, req) -> true;
    }

    public Receiver(int port, boolean encrypt, char[] passphrase, boolean resume, Path downloadDir)
    {
        this.port = port;
        this.encrypt = encrypt;
        this.passphrase = passphrase != null ? passphrase.clone() : new char[0];
        this.resume = resume;
        this.downloadDir = (downloadDir != null ? downloadDir : Path.of(".")).toAbsolutePath().normalize();
    }

    public Receiver(int port, boolean encrypt, char[] passphrase, boolean resume)
    {
        this(port, encrypt, passphrase, resume, Path.of("."));
    }

    public Receiver(int port, boolean encrypt, String passphrase, boolean resume)
    {
        this(port, encrypt, passphrase != null ? passphrase.toCharArray() : new char[0], resume, Path.of("."));
    }

    public Receiver(int port, boolean encrypt, String passphrase, boolean resume, Path downloadDir)
    {
        this(port, encrypt, passphrase != null ? passphrase.toCharArray() : new char[0], resume, downloadDir);
    }

    public Path getDownloadDir()
    {
        return downloadDir;
    }

    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException
    {
        return readyLatch.await(timeout, unit);
    }

    public void start() throws Exception
    {
        Files.createDirectories(downloadDir);
        try
        {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            serverSocket.setReceiveBufferSize(256 * 1024);
            readyLatch.countDown();
        }
        catch (Exception e)
        {
            readyLatch.countDown();
            throw e;
        }

        System.out.println("Listening for incoming transfers on port " + port + "... (saving to: " + downloadDir + ")");

        while (running)
        {
            try
            {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(256 * 1024);
                socket.setSendBufferSize(256 * 1024);
                socket.setSoTimeout(socketTimeoutMs);

                executor.submit(() ->
                {
                    try
                    {
                        handleConnection(socket);
                    }
                    catch (Exception e)
                    {
                        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        System.err.println("Error handling transfer: " + msg);
                    }
                });
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
        Crypto.wipe(passphrase);
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
        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS))
            {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close()
    {
        stop();
    }

    private void handleConnection(Socket socket) throws Exception
    {
        SecretKeySpec secretKey = null;

        try (InputStream in = socket.getInputStream();
             DataInputStream dataIn = new DataInputStream(in);
             OutputStream out = socket.getOutputStream();
             DataOutputStream dataOut = new DataOutputStream(out))
        {
            int handshakeMagic = dataIn.readInt();
            String fileName;
            long totalSize;
            byte[] expectedChecksum = new byte[0];
            String transferId = UUID.randomUUID().toString();
            int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;
            byte[] salt = new byte[0];
            byte[] ivMeta = new byte[0];
            byte[] ivData = new byte[0];

            if (handshakeMagic == 0x41525354)
            {
                int protocolVersion = dataIn.readUnsignedByte();

                if (protocolVersion == 4)
                {
                    handleV4FramedTransfer(socket, dataIn, dataOut);
                    return;
                }
                else if (protocolVersion == 2)
                {
                    if (!this.encrypt)
                    {
                        throw new IOException("Encrypted transfer rejected: receiver is not running in --encrypt mode");
                    }

                    salt = new byte[Crypto.SALT_LENGTH];
                    ivMeta = new byte[Crypto.IV_LENGTH];
                    ivData = new byte[Crypto.IV_LENGTH];
                    dataIn.readFully(salt);
                    dataIn.readFully(ivMeta);
                    dataIn.readFully(ivData);

                    int encryptedMetaLen = dataIn.readInt();
                    if (encryptedMetaLen <= 0 || encryptedMetaLen > 65536)
                    {
                        throw new IOException("Invalid encrypted metadata length: " + encryptedMetaLen);
                    }
                    byte[] encryptedMeta = new byte[encryptedMetaLen];
                    dataIn.readFully(encryptedMeta);

                    secretKey = Crypto.deriveKey(passphrase, salt);
                    Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, Cipher.DECRYPT_MODE);
                    byte[] rawMeta;
                    try
                    {
                        rawMeta = metaCipher.doFinal(encryptedMeta);
                    }
                    catch (Exception e)
                    {
                        throw new IOException("Authentication failed: invalid passphrase or corrupted encrypted metadata", e);
                    }

                    try (DataInputStream metaIn = new DataInputStream(new ByteArrayInputStream(rawMeta)))
                    {
                        int nameLength = metaIn.readInt();
                        if (nameLength <= 0 || nameLength > 4096)
                        {
                            throw new IOException("Invalid filename length in encrypted metadata: " + nameLength);
                        }
                        byte[] nameBytes = new byte[nameLength];
                        metaIn.readFully(nameBytes);
                        fileName = new String(nameBytes, StandardCharsets.UTF_8);
                        totalSize = metaIn.readLong();
                        if (totalSize < 0)
                        {
                            throw new IOException("Invalid negative file size in encrypted metadata: " + totalSize);
                        }

                        int checksumLength = metaIn.readInt();
                        if (checksumLength < 0 || checksumLength > 64)
                        {
                            throw new IOException("Invalid checksum length in metadata: " + checksumLength);
                        }
                        if (checksumLength > 0)
                        {
                            if (checksumLength != 32)
                            {
                                throw new IOException("Invalid SHA-256 checksum length: " + checksumLength);
                            }
                            expectedChecksum = new byte[checksumLength];
                            metaIn.readFully(expectedChecksum);
                        }

                        if (metaIn.available() >= 4)
                        {
                            int idLen = metaIn.readInt();
                            if (idLen > 0 && idLen <= 128)
                            {
                                byte[] idBytes = new byte[idLen];
                                metaIn.readFully(idBytes);
                                transferId = new String(idBytes, StandardCharsets.UTF_8);
                            }
                        }

                        if (metaIn.available() >= 4)
                        {
                            int cSize = metaIn.readInt();
                            if (cSize > 0 && cSize <= 8 * 1024 * 1024)
                            {
                                chunkSize = cSize;
                            }
                        }
                        else
                        {
                            chunkSize = 0; // Legacy streaming fallback
                        }
                    }
                }
                else if (protocolVersion == 3)
                {
                    if (this.encrypt)
                    {
                        throw new IOException("Unencrypted transfer rejected: receiver requires an encrypted session (--encrypt)");
                    }

                    int nameLength = dataIn.readInt();
                    if (nameLength <= 0 || nameLength > 4096)
                    {
                        throw new IOException("Invalid filename length in metadata: " + nameLength);
                    }
                    byte[] nameBytes = new byte[nameLength];
                    dataIn.readFully(nameBytes);
                    fileName = new String(nameBytes, StandardCharsets.UTF_8);
                    totalSize = dataIn.readLong();
                    if (totalSize < 0)
                    {
                        throw new IOException("Invalid negative file size in metadata: " + totalSize);
                    }

                    int checksumLength = dataIn.readInt();
                    if (checksumLength < 0 || checksumLength > 64)
                    {
                        throw new IOException("Invalid checksum length in metadata: " + checksumLength);
                    }
                    if (checksumLength > 0)
                    {
                        if (checksumLength != 32)
                        {
                            throw new IOException("Invalid SHA-256 checksum length: " + checksumLength);
                        }
                        expectedChecksum = new byte[checksumLength];
                        dataIn.readFully(expectedChecksum);
                    }

                    int idLen = dataIn.readInt();
                    if (idLen > 0 && idLen <= 128)
                    {
                        byte[] idBytes = new byte[idLen];
                        dataIn.readFully(idBytes);
                        transferId = new String(idBytes, StandardCharsets.UTF_8);
                    }
                }
                else if (protocolVersion == 1)
                {
                    if (this.encrypt)
                    {
                        throw new IOException("Unencrypted transfer rejected: receiver requires an encrypted session (--encrypt)");
                    }

                    int nameLength = dataIn.readInt();
                    if (nameLength <= 0 || nameLength > 4096)
                    {
                        throw new IOException("Invalid filename length in metadata: " + nameLength);
                    }
                    byte[] nameBytes = new byte[nameLength];
                    dataIn.readFully(nameBytes);
                    fileName = new String(nameBytes, StandardCharsets.UTF_8);
                    totalSize = dataIn.readLong();
                    if (totalSize < 0)
                    {
                        throw new IOException("Invalid negative file size in metadata: " + totalSize);
                    }

                    int checksumLength = dataIn.readInt();
                    if (checksumLength < 0 || checksumLength > 64)
                    {
                        throw new IOException("Invalid checksum length in metadata: " + checksumLength);
                    }
                    if (checksumLength > 0)
                    {
                        if (checksumLength != 32)
                        {
                            throw new IOException("Invalid SHA-256 checksum length: " + checksumLength);
                        }
                        expectedChecksum = new byte[checksumLength];
                        dataIn.readFully(expectedChecksum);
                    }
                }
                else
                {
                    UUID zeroId = new UUID(0L, 0L);
                    try
                    {
                        Frame err = Frame.error(zeroId, ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocol version: " + protocolVersion);
                        err.writeTo(dataOut);
                    }
                    catch (Exception ignored)
                    {
                    }
                    throw new AirSocketProtocolException(ErrorCode.UNSUPPORTED_VERSION, zeroId, "Unsupported protocol version: " + protocolVersion);
                }
            }
            else
            {
                if (this.encrypt)
                {
                    throw new IOException("Unencrypted transfer rejected: receiver requires an encrypted session (--encrypt)");
                }
                int nameLength = handshakeMagic;
                if (nameLength <= 0 || nameLength > 255)
                {
                    throw new IOException("Invalid legacy handshake: invalid name length " + nameLength);
                }
                byte[] nameBytes = new byte[nameLength];
                dataIn.readFully(nameBytes);
                fileName = new String(nameBytes, StandardCharsets.UTF_8);
                totalSize = dataIn.readLong();
                if (totalSize < 0)
                {
                    throw new IOException("Invalid negative file size in legacy metadata: " + totalSize);
                }
            }

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

            Path finalPath = resolveSafeDestination(fileName);
            File partFile = finalPath.resolveSibling(finalPath.getFileName().toString() + ".part").toFile();
            File metaFile = finalPath.resolveSibling(finalPath.getFileName().toString() + ".part.meta").toFile();

            long offset = 0;
            boolean append = false;

            if (resume && partFile.exists() && metaFile.exists())
            {
                TransferMetadata loadedMeta = TransferMetadata.load(metaFile.toPath());
                if (loadedMeta != null && loadedMeta.matches(fileName, totalSize, expectedChecksum, encrypt))
                {
                    long metaOffset = loadedMeta.offset();
                    long currentPartLen = partFile.length();
                    offset = Math.min(metaOffset, currentPartLen);
                    if (chunkSize > 0)
                    {
                        offset = (offset / chunkSize) * chunkSize;
                    }

                    if (currentPartLen > offset)
                    {
                        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(partFile.toPath(), java.nio.file.StandardOpenOption.WRITE))
                        {
                            fc.truncate(offset);
                        }
                    }
                    append = offset > 0;
                }
                else
                {
                    offset = 0;
                    append = false;
                }
            }

            long requiredSpace = Math.max(0L, totalSize - offset);
            if (!diskSpaceValidator.hasSpace(downloadDir, requiredSpace))
            {
                throw new IOException("Insufficient disk space: required " + requiredSpace + " bytes on " + downloadDir);
            }

            if (offset > 0)
            {
                dataOut.write(("RESUME:" + offset + "\n").getBytes(StandardCharsets.UTF_8));
            }
            else
            {
                dataOut.write(0x06);
            }
            dataOut.flush();

            long bytesReceived = offset;

            try
            {
                if (encrypt && chunkSize > 0)
                {
                    // Chunk-based AES-GCM receiving loop
                    long currentOffset = offset;
                    long chunkIndex = currentOffset / chunkSize;

                    try (FileOutputStream fos = new FileOutputStream(partFile, append))
                    {
                        while (currentOffset < totalSize)
                        {
                            int expectedPlainLen = (int) Math.min((long) chunkSize, totalSize - currentOffset);
                            int expectedCipherLen = expectedPlainLen + 16;

                            int incomingChunkLen = dataIn.readInt();
                            if (incomingChunkLen != expectedCipherLen)
                            {
                                throw new IOException("Malformed chunk framing: expected " + expectedCipherLen + " bytes, got " + incomingChunkLen);
                            }

                            byte[] cipherBuf = new byte[incomingChunkLen];
                            dataIn.readFully(cipherBuf);

                            byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                            Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.DECRYPT_MODE);
                            byte[] plainChunk = chunkCipher.doFinal(cipherBuf);

                            if (plainChunk.length != expectedPlainLen)
                            {
                                throw new IOException("Decrypted chunk length mismatch: expected " + expectedPlainLen + ", got " + plainChunk.length);
                            }

                            fos.write(plainChunk);
                            fos.flush();

                            currentOffset += plainChunk.length;
                            chunkIndex++;

                            TransferMetadata currentMeta = TransferMetadata.create(
                                transferId, fileName, totalSize, expectedChecksum, chunkSize, currentOffset, true, salt, ivMeta, ivData
                            );
                            currentMeta.save(metaFile.toPath());
                        }
                    }
                    bytesReceived = currentOffset;
                }
                else if (!encrypt)
                {
                    // Unencrypted chunk-tracked transfer loop
                    byte[] buffer = new byte[65536];
                    long currentOffset = offset;
                    long expectedBytesToRead = totalSize - offset;
                    long currentSessionBytes = 0;
                    long lastMetaSyncNanos = System.nanoTime();
                    long lastMetaBytes = currentOffset;

                    try (FileOutputStream fos = new FileOutputStream(partFile, append))
                    {
                        try
                        {
                            while (currentSessionBytes < expectedBytesToRead)
                            {
                                int toRead = (int) Math.min(buffer.length, expectedBytesToRead - currentSessionBytes);
                                int read = dataIn.read(buffer, 0, toRead);
                                if (read == -1)
                                {
                                    throw new IOException("Stream ended prematurely");
                                }
                                fos.write(buffer, 0, read);
                                currentOffset += read;
                                currentSessionBytes += read;

                                long now = System.nanoTime();
                                if ((currentOffset - lastMetaBytes) >= META_FLUSH_BYTES || (now - lastMetaSyncNanos) >= META_FLUSH_INTERVAL_NANOS || currentOffset == totalSize)
                                {
                                    TransferMetadata currentMeta = TransferMetadata.create(
                                        transferId, fileName, totalSize, expectedChecksum, chunkSize, currentOffset, false, null, null, null
                                    );
                                    currentMeta.save(metaFile.toPath());
                                    lastMetaBytes = currentOffset;
                                    lastMetaSyncNanos = now;
                                }
                            }
                        }
                        finally
                        {
                            if (resume && currentOffset > 0 && currentOffset < totalSize)
                            {
                                fos.flush();
                                try
                                {
                                    TransferMetadata currentMeta = TransferMetadata.create(
                                        transferId, fileName, totalSize, expectedChecksum, chunkSize, currentOffset, false, null, null, null
                                    );
                                    currentMeta.save(metaFile.toPath());
                                }
                                catch (IOException ignored)
                                {
                                }
                            }
                        }
                    }
                    bytesReceived = currentOffset;
                }
                else
                {
                    // Streaming AES-GCM fallback for legacy/mock clients
                    Cipher dataCipher = Crypto.getCipher(secretKey, ivData, Cipher.DECRYPT_MODE);
                    byte[] buffer = new byte[65536];
                    long currentOffset = offset;
                    long expectedBytesToRead = totalSize - offset;
                    long currentSessionBytes = 0;

                    try (CipherInputStream targetIn = new CipherInputStream(dataIn, dataCipher);
                         FileOutputStream fos = new FileOutputStream(partFile, append))
                    {
                        try
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
                                currentOffset += read;
                                currentSessionBytes += read;
                            }
                            int extra = targetIn.read();
                            if (extra != -1)
                            {
                                throw new IOException("Unexpected extra data in encrypted stream after expected payload");
                            }
                        }
                        finally
                        {
                            if (resume && currentOffset > 0 && currentOffset < totalSize)
                            {
                                fos.flush();
                                try
                                {
                                    TransferMetadata currentMeta = TransferMetadata.create(
                                        transferId, fileName, totalSize, expectedChecksum, chunkSize, currentOffset, true, salt, ivMeta, ivData
                                    );
                                    currentMeta.save(metaFile.toPath());
                                }
                                catch (IOException ignored)
                                {
                                }
                            }
                        }
                    }
                    bytesReceived = currentOffset;
                }

                if (bytesReceived != totalSize)
                {
                    throw new IOException("Transfer interrupted: received " + bytesReceived + " of " + totalSize + " bytes");
                }

                if (expectedChecksum.length > 0)
                {
                    byte[] actualChecksum = computeSha256(partFile);
                    if (!MessageDigest.isEqual(expectedChecksum, actualChecksum))
                    {
                        partFile.delete();
                        metaFile.delete();
                        throw new IOException("Checksum mismatch, transfer rejected");
                    }
                }

                Path destinationFile = resolveCollision(finalPath);
                File resolvedFile = destinationFile.toFile();

                if (partFile.renameTo(resolvedFile))
                {
                    metaFile.delete();
                    System.out.printf("[%s] Transfer complete: saved %s (%d bytes)%n", transferId, resolvedFile.getName(), totalSize);
                }
                else
                {
                    throw new IOException("Failed to rename temporary file to: " + resolvedFile.getAbsolutePath());
                }
            }
            catch (Exception e)
            {
                boolean isAuthOrIntegrityFailure = e instanceof javax.crypto.AEADBadTagException
                    || (e.getCause() instanceof javax.crypto.AEADBadTagException)
                    || (e.getMessage() != null && e.getMessage().contains("Tag mismatch"))
                    || (e.getMessage() != null && e.getMessage().contains("Checksum mismatch"));

                if (isAuthOrIntegrityFailure || !resume)
                {
                    if (partFile.exists())
                    {
                        partFile.delete();
                    }
                    if (metaFile.exists())
                    {
                        metaFile.delete();
                    }
                }
                throw e;
            }
        }
        finally
        {
            socket.close();
        }
    }

    private void handleV4FramedTransfer(Socket socket, DataInputStream dataIn, DataOutputStream dataOut) throws Exception
    {
        byte typeByte = dataIn.readByte();
        FrameType type = FrameType.fromCode(typeByte);
        byte flags = dataIn.readByte();
        dataIn.readByte(); // reserved
        long msb = dataIn.readLong();
        long lsb = dataIn.readLong();
        UUID transferId = new UUID(msb, lsb);
        int payloadLen = dataIn.readInt();
        if (payloadLen < 0 || payloadLen > Frame.MAX_PAYLOAD_SIZE)
        {
            Frame err = Frame.error(transferId, ErrorCode.MALFORMED_FRAME, "Invalid payload length: " + payloadLen);
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.MALFORMED_FRAME, transferId, "Invalid payload length: " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        dataIn.readFully(payload);

        TransferStateMachine stateMachine = new TransferStateMachine(transferId);
        try
        {
            stateMachine.validateIncomingFrame(type);
        }
        catch (AirSocketProtocolException e)
        {
            Frame err = Frame.error(transferId, ErrorCode.STATE_VIOLATION, e.getMessage());
            err.writeTo(dataOut);
            throw e;
        }

        stateMachine.transition(TransferState.HANDSHAKING);

        boolean isEncrypted = (flags & Frame.FLAG_ENCRYPTED) != 0;
        boolean isResume = (flags & Frame.FLAG_RESUME) != 0;

        if (this.encrypt && !isEncrypted)
        {
            Frame err = Frame.error(transferId, ErrorCode.TRANSFER_REJECTED, "Unencrypted transfer rejected: receiver requires an encrypted session (--encrypt)");
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.TRANSFER_REJECTED, transferId, "Unencrypted transfer rejected: receiver requires an encrypted session (--encrypt)");
        }
        if (!this.encrypt && isEncrypted)
        {
            Frame err = Frame.error(transferId, ErrorCode.TRANSFER_REJECTED, "Encrypted transfer rejected: receiver is not running in --encrypt mode");
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.TRANSFER_REJECTED, transferId, "Encrypted transfer rejected: receiver is not running in --encrypt mode");
        }

        String fileName;
        long totalSize;
        byte[] expectedChecksum = new byte[0];
        int chunkSize = Chunk.DEFAULT_CHUNK_SIZE;
        byte[] salt = new byte[0];
        byte[] ivMeta = new byte[0];
        byte[] ivData = new byte[0];
        SecretKeySpec secretKey = null;

        if (isEncrypted)
        {
            try (DataInputStream pin = new DataInputStream(new ByteArrayInputStream(payload)))
            {
                salt = new byte[Crypto.SALT_LENGTH];
                ivMeta = new byte[Crypto.IV_LENGTH];
                ivData = new byte[Crypto.IV_LENGTH];
                pin.readFully(salt);
                pin.readFully(ivMeta);
                pin.readFully(ivData);
                int encMetaLen = pin.readInt();
                if (encMetaLen <= 0 || encMetaLen > 65536)
                {
                    Frame err = Frame.error(transferId, ErrorCode.MALFORMED_FRAME, "Invalid encrypted metadata length: " + encMetaLen);
                    err.writeTo(dataOut);
                    throw new AirSocketProtocolException(ErrorCode.MALFORMED_FRAME, transferId, "Invalid encrypted metadata length: " + encMetaLen);
                }
                byte[] encMeta = new byte[encMetaLen];
                pin.readFully(encMeta);

                secretKey = Crypto.deriveKey(passphrase, salt);
                Cipher metaCipher = Crypto.getCipher(secretKey, ivMeta, Cipher.DECRYPT_MODE);
                byte[] rawMeta;
                try
                {
                    rawMeta = metaCipher.doFinal(encMeta);
                }
                catch (Exception e)
                {
                    Frame err = Frame.error(transferId, ErrorCode.AUTH_FAILED, "Authentication failed: invalid passphrase or corrupted encrypted metadata");
                    err.writeTo(dataOut);
                    throw new AirSocketProtocolException(ErrorCode.AUTH_FAILED, transferId, "Authentication failed: invalid passphrase or corrupted encrypted metadata", e);
                }

                try (DataInputStream metaIn = new DataInputStream(new ByteArrayInputStream(rawMeta)))
                {
                    int authMagic = metaIn.readInt();
                    if (authMagic != 0x41555448)
                    {
                        Frame err = Frame.error(transferId, ErrorCode.AUTH_FAILED, "Authentication failed: invalid auth magic header");
                        err.writeTo(dataOut);
                        throw new AirSocketProtocolException(ErrorCode.AUTH_FAILED, transferId, "Invalid auth magic header");
                    }
                    int tokenLen = metaIn.readInt();
                    byte[] tokenBytes = new byte[tokenLen];
                    metaIn.readFully(tokenBytes);
                    byte[] expectedToken = "AirSocket-V4-Token".getBytes(StandardCharsets.UTF_8);
                    if (!MessageDigest.isEqual(expectedToken, tokenBytes))
                    {
                        Frame err = Frame.error(transferId, ErrorCode.AUTH_FAILED, "Authentication failed: auth token mismatch");
                        err.writeTo(dataOut);
                        throw new AirSocketProtocolException(ErrorCode.AUTH_FAILED, transferId, "Auth token mismatch");
                    }

                    int nameLen = metaIn.readInt();
                    if (nameLen <= 0 || nameLen > 4096)
                    {
                        Frame err = Frame.error(transferId, ErrorCode.MALFORMED_FRAME, "Invalid filename length: " + nameLen);
                        err.writeTo(dataOut);
                        throw new AirSocketProtocolException(ErrorCode.MALFORMED_FRAME, transferId, "Invalid filename length: " + nameLen);
                    }
                    byte[] nameBytes = new byte[nameLen];
                    metaIn.readFully(nameBytes);
                    fileName = new String(nameBytes, StandardCharsets.UTF_8);
                    totalSize = metaIn.readLong();
                    int chkLen = metaIn.readInt();
                    if (chkLen > 0)
                    {
                        expectedChecksum = new byte[chkLen];
                        metaIn.readFully(expectedChecksum);
                    }
                    if (metaIn.available() >= 4)
                    {
                        chunkSize = metaIn.readInt();
                    }
                }
            }
        }
        else
        {
            try (DataInputStream pin = new DataInputStream(new ByteArrayInputStream(payload)))
            {
                int nameLen = pin.readInt();
                if (nameLen <= 0 || nameLen > 4096)
                {
                    Frame err = Frame.error(transferId, ErrorCode.MALFORMED_FRAME, "Invalid filename length: " + nameLen);
                    err.writeTo(dataOut);
                    throw new AirSocketProtocolException(ErrorCode.MALFORMED_FRAME, transferId, "Invalid filename length: " + nameLen);
                }
                byte[] nameBytes = new byte[nameLen];
                pin.readFully(nameBytes);
                fileName = new String(nameBytes, StandardCharsets.UTF_8);
                totalSize = pin.readLong();
                int chkLen = pin.readInt();
                if (chkLen > 0)
                {
                    expectedChecksum = new byte[chkLen];
                    pin.readFully(expectedChecksum);
                }
                if (pin.available() >= 4)
                {
                    chunkSize = pin.readInt();
                }
            }
        }

        // Validate metadata
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\"))
        {
            Frame err = Frame.error(transferId, ErrorCode.PATH_TRAVERSAL, "Path traversal or illegal path characters detected: " + fileName);
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.PATH_TRAVERSAL, transferId, "Path traversal or illegal path characters detected: " + fileName);
        }
        if (totalSize < 0)
        {
            Frame err = Frame.error(transferId, ErrorCode.MALFORMED_FRAME, "Invalid negative file size in metadata: " + totalSize);
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.MALFORMED_FRAME, transferId, "Invalid negative file size in metadata: " + totalSize);
        }
        if (expectedChecksum.length != 0 && expectedChecksum.length != 32)
        {
            Frame err = Frame.error(transferId, ErrorCode.CHECKSUM_MISMATCH, "Invalid SHA-256 checksum length: " + expectedChecksum.length);
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.CHECKSUM_MISMATCH, transferId, "Invalid SHA-256 checksum length: " + expectedChecksum.length);
        }

        Path finalPath = resolveSafeDestination(fileName);
        File partFile = finalPath.resolveSibling(finalPath.getFileName().toString() + ".part").toFile();
        File metaFile = finalPath.resolveSibling(finalPath.getFileName().toString() + ".part.meta").toFile();

        long offset = 0;
        boolean append = false;

        if (isResume && partFile.exists() && metaFile.exists())
        {
            TransferMetadata loadedMeta = TransferMetadata.load(metaFile.toPath());
            if (loadedMeta != null && loadedMeta.matches(fileName, totalSize, expectedChecksum, isEncrypted))
            {
                long metaOffset = loadedMeta.offset();
                long currentPartLen = partFile.length();
                offset = Math.min(metaOffset, currentPartLen);
                if (chunkSize > 0)
                {
                    offset = (offset / chunkSize) * chunkSize;
                }
                if (currentPartLen > offset)
                {
                    try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(partFile.toPath(), java.nio.file.StandardOpenOption.WRITE))
                    {
                        fc.truncate(offset);
                    }
                }
                append = offset > 0;
            }
        }

        long requiredSpace = Math.max(0L, totalSize - offset);
        if (!diskSpaceValidator.hasSpace(downloadDir, requiredSpace))
        {
            Frame err = Frame.error(transferId, ErrorCode.INSUFFICIENT_SPACE, "Insufficient disk space: required " + requiredSpace + " bytes on " + downloadDir);
            err.writeTo(dataOut);
            throw new AirSocketProtocolException(ErrorCode.INSUFFICIENT_SPACE, transferId, "Insufficient disk space: required " + requiredSpace + " bytes on " + downloadDir);
        }

        stateMachine.transition(TransferState.READY);
        Frame ackFrame = Frame.handshakeAck(transferId, ProtocolVersion.V4, offset);
        ackFrame.writeTo(dataOut);

        stateMachine.transition(TransferState.TRANSFERRING);
        long currentOffset = offset;

        try
        {
            try (FileOutputStream fos = new FileOutputStream(partFile, append))
            {
                while (currentOffset < totalSize)
                {
                    Frame frame = Frame.readFrom(dataIn);
                    if (frame.type() == FrameType.ERROR)
                    {
                        throw new AirSocketProtocolException(frame.parseErrorCode(), transferId, frame.parseErrorMessage());
                    }

                    stateMachine.validateIncomingFrame(frame.type());

                    if (!frame.transferId().equals(transferId))
                    {
                        Frame err = Frame.error(transferId, ErrorCode.INVALID_TRANSFER_ID, "Transfer ID mismatch in frame: " + frame.transferId());
                        err.writeTo(dataOut);
                        throw new AirSocketProtocolException(ErrorCode.INVALID_TRANSFER_ID, transferId, "Transfer ID mismatch in frame: " + frame.transferId());
                    }

                    if (frame.type() == FrameType.PING)
                    {
                        Frame.pong(transferId).writeTo(dataOut);
                        continue;
                    }

                    if (frame.type() == FrameType.CHUNK_DATA)
                    {
                        long chunkIndex = frame.parseChunkIndex();
                        byte[] chunkPayload = frame.parseChunkData();
                        byte[] plainChunk;

                        if (isEncrypted)
                        {
                            byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                            Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.DECRYPT_MODE);
                            try
                            {
                                plainChunk = chunkCipher.doFinal(chunkPayload);
                            }
                            catch (Exception e)
                            {
                                partFile.delete();
                                metaFile.delete();
                                Frame err = Frame.error(transferId, ErrorCode.CHECKSUM_MISMATCH, "Chunk decryption / AEAD tag authentication failed");
                                err.writeTo(dataOut);
                                throw new AirSocketProtocolException(ErrorCode.CHECKSUM_MISMATCH, transferId, "Chunk decryption / AEAD tag authentication failed", e);
                            }
                        }
                        else
                        {
                            plainChunk = chunkPayload;
                        }

                        fos.write(plainChunk);
                        fos.flush();
                        currentOffset += plainChunk.length;

                        TransferMetadata currentMeta = TransferMetadata.create(
                            transferId.toString(), fileName, totalSize, expectedChecksum, chunkSize, currentOffset,
                            isEncrypted, salt, ivMeta, ivData
                        );
                        currentMeta.save(metaFile.toPath());
                    }
                }
            }

            Frame doneFrame = Frame.readFrom(dataIn);
            if (doneFrame.type() == FrameType.ERROR)
            {
                throw new AirSocketProtocolException(doneFrame.parseErrorCode(), transferId, doneFrame.parseErrorMessage());
            }
            stateMachine.validateIncomingFrame(doneFrame.type());
            if (!doneFrame.transferId().equals(transferId))
            {
                Frame err = Frame.error(transferId, ErrorCode.INVALID_TRANSFER_ID, "Transfer ID mismatch in done frame: expected " + transferId + " but received " + doneFrame.transferId());
                err.writeTo(dataOut);
                throw new AirSocketProtocolException(ErrorCode.INVALID_TRANSFER_ID, transferId, "Transfer ID mismatch in done frame: expected " + transferId + " but received " + doneFrame.transferId());
            }
            stateMachine.transition(TransferState.FINALIZING);

            if (expectedChecksum.length > 0)
            {
                byte[] actualChecksum = computeSha256(partFile);
                if (!MessageDigest.isEqual(expectedChecksum, actualChecksum))
                {
                    partFile.delete();
                    metaFile.delete();
                    Frame err = Frame.error(transferId, ErrorCode.CHECKSUM_MISMATCH, "Checksum mismatch, transfer rejected");
                    err.writeTo(dataOut);
                    throw new AirSocketProtocolException(ErrorCode.CHECKSUM_MISMATCH, transferId, "Checksum mismatch, transfer rejected");
                }
            }

            Path destinationFile = resolveCollision(finalPath);
            File resolvedFile = destinationFile.toFile();

            if (partFile.renameTo(resolvedFile))
            {
                metaFile.delete();
                stateMachine.transition(TransferState.COMPLETED);
                Frame completeFrame = Frame.transferAck(transferId);
                completeFrame.writeTo(dataOut);
                stateMachine.transition(TransferState.CLOSED);
                System.out.printf("[%s] Transfer complete: saved %s (%d bytes)%n", transferId, resolvedFile.getName(), totalSize);
            }
            else
            {
                Frame err = Frame.error(transferId, ErrorCode.INTERNAL_ERROR, "Failed to rename temporary file to destination");
                err.writeTo(dataOut);
                throw new AirSocketProtocolException(ErrorCode.INTERNAL_ERROR, transferId, "Failed to rename temporary file to destination");
            }
        }
        catch (Exception e)
        {
            stateMachine.fail();
            boolean isAuthOrIntegrityFailure = e instanceof javax.crypto.AEADBadTagException
                || (e.getCause() instanceof javax.crypto.AEADBadTagException)
                || (e.getMessage() != null && e.getMessage().contains("Tag mismatch"))
                || (e.getMessage() != null && e.getMessage().contains("Checksum mismatch"));

            if (isAuthOrIntegrityFailure || !resume)
            {
                if (partFile.exists()) partFile.delete();
                if (metaFile.exists()) metaFile.delete();
            }

            try
            {
                if (!socket.isClosed() && socket.isConnected())
                {
                    ErrorCode code = (e instanceof AirSocketProtocolException ape) ? ape.getErrorCode() : ErrorCode.INTERNAL_ERROR;
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    Frame err = Frame.error(transferId, code, msg);
                    err.writeTo(dataOut);
                }
            }
            catch (Exception ignored)
            {
            }
            throw e;
        }
    }

    public static Path resolveCollision(Path targetPath)
    {
        if (!Files.exists(targetPath))
        {
            return targetPath;
        }

        String fileName = targetPath.getFileName().toString();
        String baseName;
        String extension;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0)
        {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }
        else
        {
            baseName = fileName;
            extension = "";
        }

        int count = 1;
        Path parent = targetPath.getParent();
        Path candidate;
        do
        {
            candidate = parent.resolve(baseName + " (" + count + ")" + extension);
            count++;
        } while (Files.exists(candidate));

        return candidate;
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

    private Path resolveSafeDestination(String rawFileName) throws IOException
    {
        if (rawFileName == null || rawFileName.isBlank() || rawFileName.contains("\0"))
        {
            throw new IOException("Invalid filename: cannot be empty or contain null bytes");
        }

        if (rawFileName.contains("/") || rawFileName.contains("\\") || rawFileName.contains(":") ||
            rawFileName.equals(".") || rawFileName.equals(".."))
        {
            throw new IOException("Path traversal or illegal path characters detected: " + rawFileName);
        }

        Path safeFileName = Path.of(rawFileName).getFileName();
        if (safeFileName == null || safeFileName.toString().isBlank() ||
            safeFileName.toString().equals(".") || safeFileName.toString().equals(".."))
        {
            throw new IOException("Illegal filename: " + rawFileName);
        }

        Path resolvedFile = downloadDir.resolve(safeFileName).normalize();
        if (!resolvedFile.startsWith(downloadDir) || !resolvedFile.getParent().equals(downloadDir))
        {
            throw new IOException("Path traversal attempt detected: " + rawFileName);
        }

        return resolvedFile;
    }
}
