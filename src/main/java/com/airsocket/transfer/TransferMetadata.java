package com.airsocket.transfer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public record TransferMetadata(
    int version,
    String transferId,
    String fileName,
    long fileSize,
    byte[] checksum,
    int chunkSize,
    long offset,
    boolean encrypted,
    byte[] salt,
    byte[] ivMeta,
    byte[] ivData
)
{
    public static final int CURRENT_VERSION = 2;

    public static TransferMetadata create(
        String transferId,
        String fileName,
        long fileSize,
        byte[] checksum,
        int chunkSize,
        long offset,
        boolean encrypted,
        byte[] salt,
        byte[] ivMeta,
        byte[] ivData
    )
    {
        return new TransferMetadata(
            CURRENT_VERSION,
            transferId != null ? transferId : "",
            fileName != null ? fileName : "",
            fileSize,
            checksum != null ? checksum.clone() : new byte[0],
            chunkSize > 0 ? chunkSize : Chunk.DEFAULT_CHUNK_SIZE,
            offset,
            encrypted,
            salt != null ? salt.clone() : new byte[0],
            ivMeta != null ? ivMeta.clone() : new byte[0],
            ivData != null ? ivData.clone() : new byte[0]
        );
    }

    public TransferMetadata withOffset(long newOffset)
    {
        return new TransferMetadata(
            version,
            transferId,
            fileName,
            fileSize,
            checksum,
            chunkSize,
            newOffset,
            encrypted,
            salt,
            ivMeta,
            ivData
        );
    }

    public void save(Path metaFile) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(version).append("\n");
        sb.append("transferId=").append(transferId).append("\n");
        sb.append("fileName=").append(fileName).append("\n");
        sb.append("fileSize=").append(fileSize).append("\n");
        sb.append("checksum=").append(toHex(checksum)).append("\n");
        sb.append("chunkSize=").append(chunkSize).append("\n");
        sb.append("offset=").append(offset).append("\n");
        sb.append("encrypted=").append(encrypted).append("\n");
        sb.append("salt=").append(toHex(salt)).append("\n");
        sb.append("ivMeta=").append(toHex(ivMeta)).append("\n");
        sb.append("ivData=").append(toHex(ivData)).append("\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        Path parent = metaFile.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        Path tmpFile = metaFile.resolveSibling(metaFile.getFileName().toString() + ".tmp");
        Files.write(tmpFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        try
        {
            Files.move(tmpFile, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmpFile, metaFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static TransferMetadata load(Path metaFile)
    {
        if (metaFile == null || !Files.exists(metaFile))
        {
            return null;
        }

        try
        {
            byte[] rawBytes = Files.readAllBytes(metaFile);
            String content = new String(rawBytes, StandardCharsets.UTF_8).trim();
            if (content.isEmpty())
            {
                return null;
            }

            // Check if legacy purely numeric format
            if (!content.contains("=") && !content.contains("\n"))
            {
                long legacyOffset = Long.parseLong(content);
                return new TransferMetadata(
                    1,
                    "",
                    "",
                    0L,
                    new byte[0],
                    Chunk.DEFAULT_CHUNK_SIZE,
                    legacyOffset,
                    false,
                    new byte[0],
                    new byte[0],
                    new byte[0]
                );
            }

            Map<String, String> map = new HashMap<>();
            for (String line : content.split("\\r?\\n"))
            {
                int eq = line.indexOf('=');
                if (eq > 0)
                {
                    map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }

            int ver = Integer.parseInt(map.getOrDefault("version", "1"));
            String tid = map.getOrDefault("transferId", "");
            String fname = map.getOrDefault("fileName", "");
            long fsize = Long.parseLong(map.getOrDefault("fileSize", "0"));
            byte[] chk = fromHex(map.getOrDefault("checksum", ""));
            int csize = Integer.parseInt(map.getOrDefault("chunkSize", String.valueOf(Chunk.DEFAULT_CHUNK_SIZE)));
            long off = Long.parseLong(map.getOrDefault("offset", "0"));
            boolean enc = Boolean.parseBoolean(map.getOrDefault("encrypted", "false"));
            byte[] s = fromHex(map.getOrDefault("salt", ""));
            byte[] ivM = fromHex(map.getOrDefault("ivMeta", ""));
            byte[] ivD = fromHex(map.getOrDefault("ivData", ""));

            return new TransferMetadata(ver, tid, fname, fsize, chk, csize, off, enc, s, ivM, ivD);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public boolean matches(String incomingFileName, long incomingFileSize, byte[] incomingChecksum, boolean incomingEncrypted)
    {
        if (this.version == 1 && this.fileName.isEmpty() && this.fileSize == 0)
        {
            // Legacy format containing only offset: allow resume
            return true;
        }

        if (this.fileName != null && !this.fileName.isEmpty() && !this.fileName.equals(incomingFileName))
        {
            return false;
        }

        if (this.fileSize > 0 && incomingFileSize > 0 && this.fileSize != incomingFileSize)
        {
            return false;
        }

        if (this.checksum != null && this.checksum.length > 0 && incomingChecksum != null && incomingChecksum.length > 0)
        {
            if (!MessageDigest.isEqual(this.checksum, incomingChecksum))
            {
                return false;
            }
        }

        if (this.encrypted != incomingEncrypted)
        {
            return false;
        }

        return true;
    }

    public static String toHex(byte[] data)
    {
        if (data == null || data.length == 0)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data)
        {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex)
    {
        if (hex == null || hex.isEmpty())
        {
            return new byte[0];
        }
        int len = hex.length();
        if (len % 2 != 0)
        {
            return new byte[0];
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
        {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1)
            {
                return new byte[0];
            }
            out[i / 2] = (byte) ((high << 4) + low);
        }
        return out;
    }
}
