package com.airsocket.transfer;

public record Chunk(long index, long offset, int length, boolean isLast)
{
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64 KB

    public static long totalChunks(long fileSize, int chunkSize)
    {
        if (chunkSize <= 0)
        {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        if (fileSize < 0)
        {
            throw new IllegalArgumentException("fileSize cannot be negative: " + fileSize);
        }
        if (fileSize == 0)
        {
            return 0;
        }
        return (fileSize + chunkSize - 1) / chunkSize;
    }

    public static Chunk of(long index, long fileSize, int chunkSize)
    {
        if (chunkSize <= 0)
        {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        if (fileSize < 0)
        {
            throw new IllegalArgumentException("fileSize cannot be negative: " + fileSize);
        }
        long total = totalChunks(fileSize, chunkSize);
        if (index < 0 || (total > 0 && index >= total) || (total == 0 && index > 0))
        {
            throw new IndexOutOfBoundsException("Chunk index out of bounds: " + index + " (total chunks: " + total + ")");
        }
        long offset = index * (long) chunkSize;
        int len = (int) Math.min((long) chunkSize, Math.max(0L, fileSize - offset));
        boolean isLast = (offset + len) >= fileSize;
        return new Chunk(index, offset, len, isLast);
    }
}
