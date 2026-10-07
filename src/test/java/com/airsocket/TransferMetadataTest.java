package com.airsocket;

import com.airsocket.transfer.Chunk;
import com.airsocket.transfer.TransferMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

public class TransferMetadataTest
{
    @TempDir
    Path tempDir;

    @Test
    public void testSaveAndLoadEncryptedMetadata() throws Exception
    {
        Path metaPath = tempDir.resolve("test_file.bin.part.meta");
        byte[] checksum = MessageDigest.getInstance("SHA-256", java.security.Security.getProvider("SUN") != null ? java.security.Security.getProvider("SUN") : null) != null
            ? MessageDigest.getInstance("SHA-256").digest("sample-content".getBytes(StandardCharsets.UTF_8))
            : new byte[32];
        byte[] salt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] ivMeta = new byte[]{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21};
        byte[] ivData = new byte[]{30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41};

        TransferMetadata meta = TransferMetadata.create(
            "transfer-uuid-1234",
            "test_file.bin",
            1048576L,
            checksum,
            Chunk.DEFAULT_CHUNK_SIZE,
            524288L,
            true,
            salt,
            ivMeta,
            ivData
        );

        meta.save(metaPath);
        assertTrue(Files.exists(metaPath), "Meta file should exist after save");

        Path tmpPath = metaPath.resolveSibling(metaPath.getFileName().toString() + ".tmp");
        assertFalse(Files.exists(tmpPath), "Atomic write should not leave temporary file");

        TransferMetadata loaded = TransferMetadata.load(metaPath);
        assertNotNull(loaded, "Loaded metadata should not be null");
        assertEquals("transfer-uuid-1234", loaded.transferId());
        assertEquals("test_file.bin", loaded.fileName());
        assertEquals(1048576L, loaded.fileSize());
        assertArrayEquals(checksum, loaded.checksum());
        assertEquals(Chunk.DEFAULT_CHUNK_SIZE, loaded.chunkSize());
        assertEquals(524288L, loaded.offset());
        assertTrue(loaded.encrypted());
        assertArrayEquals(salt, loaded.salt());
        assertArrayEquals(ivMeta, loaded.ivMeta());
        assertArrayEquals(ivData, loaded.ivData());
    }

    @Test
    public void testLoadLegacyOffsetFormat() throws IOException
    {
        Path metaPath = tempDir.resolve("legacy.bin.part.meta");
        Files.writeString(metaPath, "1048576", StandardCharsets.UTF_8);

        TransferMetadata loaded = TransferMetadata.load(metaPath);
        assertNotNull(loaded, "Legacy format should be parsed");
        assertEquals(1, loaded.version());
        assertEquals(1048576L, loaded.offset());
        assertFalse(loaded.encrypted());
    }

    @Test
    public void testLoadMalformedOrCorruptMetaFiles() throws IOException
    {
        assertNull(TransferMetadata.load(null));
        assertNull(TransferMetadata.load(tempDir.resolve("non_existent.meta")));

        Path emptyPath = tempDir.resolve("empty.meta");
        Files.writeString(emptyPath, "   \n  \t  ", StandardCharsets.UTF_8);
        assertNull(TransferMetadata.load(emptyPath));

        Path corruptPath = tempDir.resolve("corrupt.meta");
        Files.writeString(corruptPath, "foo=bar\nbaz\n", StandardCharsets.UTF_8);
        TransferMetadata corruptLoaded = TransferMetadata.load(corruptPath);
        assertNotNull(corruptLoaded);
        assertEquals(0L, corruptLoaded.offset());
    }

    @Test
    public void testMatchesVerification() throws Exception
    {
        byte[] checksum1 = MessageDigest.getInstance("SHA-256").digest("file1".getBytes(StandardCharsets.UTF_8));
        byte[] checksum2 = MessageDigest.getInstance("SHA-256").digest("file2".getBytes(StandardCharsets.UTF_8));

        TransferMetadata meta = TransferMetadata.create(
            "uuid-1",
            "file.bin",
            5000L,
            checksum1,
            Chunk.DEFAULT_CHUNK_SIZE,
            2000L,
            true,
            new byte[16],
            new byte[12],
            new byte[12]
        );

        // Identical parameters match
        assertTrue(meta.matches("file.bin", 5000L, checksum1, true));

        // Mismatched file name
        assertFalse(meta.matches("other.bin", 5000L, checksum1, true));

        // Mismatched file size
        assertFalse(meta.matches("file.bin", 6000L, checksum1, true));

        // Mismatched checksum
        assertFalse(meta.matches("file.bin", 5000L, checksum2, true));

        // Mismatched encryption mode
        assertFalse(meta.matches("file.bin", 5000L, checksum1, false));
    }

    @Test
    public void testHexConversionHelpers()
    {
        byte[] data = new byte[]{(byte) 0x00, (byte) 0x0F, (byte) 0xAA, (byte) 0xFF};
        String hex = TransferMetadata.toHex(data);
        assertEquals("000faaff", hex);

        byte[] back = TransferMetadata.fromHex(hex);
        assertArrayEquals(data, back);

        assertEquals("", TransferMetadata.toHex(null));
        assertEquals("", TransferMetadata.toHex(new byte[0]));
        assertArrayEquals(new byte[0], TransferMetadata.fromHex(""));
        assertArrayEquals(new byte[0], TransferMetadata.fromHex(null));
        assertArrayEquals(new byte[0], TransferMetadata.fromHex("invalid-hex-odd"));
    }
}
