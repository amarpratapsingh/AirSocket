package com.airsocket;

import com.airsocket.benchmark.ProfileSnapshot;
import com.airsocket.benchmark.SystemProfiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SystemProfilerTest
{
    @Test
    public void testProfileSnapshotAndDiff() throws Exception
    {
        ProfileSnapshot start = SystemProfiler.takeSnapshot();
        assertNotNull(start);
        assertTrue(start.timestampNanos() > 0);
        assertTrue(start.heapUsedBytes() > 0);

        // Do some minor work
        Thread.sleep(50);
        byte[] dummy = new byte[1024 * 1024];
        dummy[0] = 1;

        ProfileSnapshot end = SystemProfiler.takeSnapshot();
        assertNotNull(end);
        assertTrue(end.timestampNanos() >= start.timestampNanos());

        SystemProfiler.SystemProfileDiff diff = SystemProfiler.diff(start, end, 10L * 1024 * 1024);
        assertNotNull(diff);
        assertTrue(diff.elapsedSeconds() > 0.0);
        assertTrue(diff.throughputMbps() > 0.0);

        String report = diff.formatReport();
        assertNotNull(report);
        assertTrue(report.contains("Elapsed:"));
        assertTrue(report.contains("Throughput:"));
    }
}
