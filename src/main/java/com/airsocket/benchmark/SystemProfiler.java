package com.airsocket.benchmark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;

public class SystemProfiler
{
    public record SystemProfileDiff(
        double elapsedSeconds,
        double cpuTimeSeconds,
        double avgCpuPercent,
        long heapDeltaBytes,
        long gcCollections,
        long gcPauseMs,
        double throughputMbps,
        double mbpsPerCpuPercent
    )
    {
        public String formatReport()
        {
            return String.format(
                "Elapsed: %.3fs | CPU Time: %.3fs (Avg Load: %.1f%%) | Throughput: %.1f Mbps | Efficiency: %.2f Mbps/%%CPU | Heap Δ: %+.2f MB | GC: %d pauses (%d ms)",
                elapsedSeconds,
                cpuTimeSeconds,
                avgCpuPercent,
                throughputMbps,
                mbpsPerCpuPercent,
                heapDeltaBytes / (1024.0 * 1024.0),
                gcCollections,
                gcPauseMs
            );
        }
    }

    public static ProfileSnapshot takeSnapshot()
    {
        long nowNanos = System.nanoTime();
        long processCpuTime = 0L;
        double processCpuLoad = 0.0;
        double systemCpuLoad = 0.0;

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean)
        {
            processCpuTime = sunOsBean.getProcessCpuTime();
            processCpuLoad = sunOsBean.getProcessCpuLoad() * 100.0;
            systemCpuLoad = sunOsBean.getCpuLoad() * 100.0;
        }

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memBean.getHeapMemoryUsage().getUsed();
        long nonHeapUsed = memBean.getNonHeapMemoryUsage().getUsed();

        long totalGcCount = 0;
        long totalGcTime = 0;
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans)
        {
            long c = gc.getCollectionCount();
            if (c > 0)
            {
                totalGcCount += c;
            }
            long t = gc.getCollectionTime();
            if (t > 0)
            {
                totalGcTime += t;
            }
        }

        return new ProfileSnapshot(
            nowNanos,
            processCpuTime,
            processCpuLoad,
            systemCpuLoad,
            heapUsed,
            nonHeapUsed,
            totalGcCount,
            totalGcTime
        );
    }

    public static SystemProfileDiff diff(ProfileSnapshot start, ProfileSnapshot end, long bytesTransferred)
    {
        long elapsedNanos = Math.max(1L, end.timestampNanos() - start.timestampNanos());
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        long cpuTimeNanos = Math.max(0L, end.processCpuTimeNanos() - start.processCpuTimeNanos());
        double cpuTimeSeconds = cpuTimeNanos / 1_000_000_000.0;

        // Number of available processors to scale CPU percentage correctly
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        double avgCpuPercent = elapsedSeconds > 0
            ? (cpuTimeSeconds / (elapsedSeconds * processors)) * 100.0 * processors
            : 0.0;

        long heapDelta = end.heapUsedBytes() - start.heapUsedBytes();
        long gcCollections = Math.max(0L, end.gcCollectionCount() - start.gcCollectionCount());
        long gcPauseMs = Math.max(0L, end.gcPauseTimeMs() - start.gcPauseTimeMs());

        double throughputMbps = (bytesTransferred * 8.0) / (elapsedSeconds * 1_000_000.0);
        double mbpsPerCpu = avgCpuPercent > 0.0 ? throughputMbps / avgCpuPercent : throughputMbps;

        return new SystemProfileDiff(
            elapsedSeconds,
            cpuTimeSeconds,
            avgCpuPercent,
            heapDelta,
            gcCollections,
            gcPauseMs,
            throughputMbps,
            mbpsPerCpu
        );
    }
}
