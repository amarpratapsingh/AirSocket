package com.airsocket.benchmark;

public record ProfileSnapshot(
    long timestampNanos,
    long processCpuTimeNanos,
    double processCpuLoad,
    double systemCpuLoad,
    long heapUsedBytes,
    long nonHeapUsedBytes,
    long gcCollectionCount,
    long gcPauseTimeMs
) {}
