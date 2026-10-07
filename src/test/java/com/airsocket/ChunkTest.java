package com.airsocket;

import com.airsocket.transfer.Chunk;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ChunkTest
{
    @Test
    public void testTotalChunksCalculations()
    {
        int chunkSize = 64 * 1024; // 64 KB

        assertEquals(0, Chunk.totalChunks(0, chunkSize), "0-byte file should have 0 chunks");
        assertEquals(1, Chunk.totalChunks(1, chunkSize), "1-byte file should have 1 chunk");
        assertEquals(1, Chunk.totalChunks(chunkSize - 1, chunkSize), "File smaller than chunk size should have 1 chunk");
        assertEquals(1, Chunk.totalChunks(chunkSize, chunkSize), "File equal to chunk size should have 1 chunk");
        assertEquals(2, Chunk.totalChunks(chunkSize + 1, chunkSize), "File 1 byte over chunk size should have 2 chunks");
        assertEquals(2, Chunk.totalChunks(2 * chunkSize, chunkSize), "File exactly 2 chunks should have 2 chunks");
        assertEquals(5, Chunk.totalChunks(4 * chunkSize + 100, chunkSize), "File with partial chunk should round up");

        assertThrows(IllegalArgumentException.class, () -> Chunk.totalChunks(-1, chunkSize));
        assertThrows(IllegalArgumentException.class, () -> Chunk.totalChunks(100, 0));
        assertThrows(IllegalArgumentException.class, () -> Chunk.totalChunks(100, -10));
    }

    @Test
    public void testChunkOfMethod()
    {
        int chunkSize = 64 * 1024;
        long fileSize = (2L * chunkSize) + 500; // 2 full chunks + 1 partial chunk (500 bytes)

        // Chunk 0
        Chunk c0 = Chunk.of(0, fileSize, chunkSize);
        assertEquals(0, c0.index());
        assertEquals(0L, c0.offset());
        assertEquals(chunkSize, c0.length());
        assertFalse(c0.isLast());

        // Chunk 1
        Chunk c1 = Chunk.of(1, fileSize, chunkSize);
        assertEquals(1, c1.index());
        assertEquals(chunkSize, c1.offset());
        assertEquals(chunkSize, c1.length());
        assertFalse(c1.isLast());

        // Chunk 2 (last)
        Chunk c2 = Chunk.of(2, fileSize, chunkSize);
        assertEquals(2, c2.index());
        assertEquals(2L * chunkSize, c2.offset());
        assertEquals(500, c2.length());
        assertTrue(c2.isLast());

        // Out of bounds chunk index
        assertThrows(IndexOutOfBoundsException.class, () -> Chunk.of(3, fileSize, chunkSize));
        assertThrows(IndexOutOfBoundsException.class, () -> Chunk.of(-1, fileSize, chunkSize));
    }

    @Test
    public void testSingleChunkFile()
    {
        int chunkSize = 64 * 1024;
        long fileSize = 1000;

        Chunk c0 = Chunk.of(0, fileSize, chunkSize);
        assertEquals(0, c0.index());
        assertEquals(0L, c0.offset());
        assertEquals(1000, c0.length());
        assertTrue(c0.isLast());
    }
}
