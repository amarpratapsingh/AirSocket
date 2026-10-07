package com.airsocket;

import com.airsocket.benchmark.BenchmarkStats;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BenchmarkStatsTest
{
    @Test
    public void testEmptyAndSingleElementLists()
    {
        BenchmarkStats empty = BenchmarkStats.compute(Collections.emptyList());
        assertEquals(0, empty.count());
        assertEquals(0.0, empty.mean());
        assertEquals(0.0, empty.median());
        assertEquals(0.0, empty.stdDev());

        BenchmarkStats single = BenchmarkStats.compute(List.of(100.0));
        assertEquals(1, single.count());
        assertEquals(100.0, single.mean());
        assertEquals(100.0, single.median());
        assertEquals(100.0, single.min());
        assertEquals(100.0, single.max());
        assertEquals(0.0, single.stdDev());
        assertEquals(0.0, single.coefficientOfVariation());
    }

    @Test
    public void testKnownDistributionCalculations()
    {
        // Values: 10, 20, 30, 40, 50
        List<Double> values = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        BenchmarkStats stats = BenchmarkStats.compute(values);

        assertEquals(5, stats.count());
        assertEquals(30.0, stats.mean(), 0.001);
        assertEquals(30.0, stats.median(), 0.001);
        assertEquals(10.0, stats.min(), 0.001);
        assertEquals(50.0, stats.max(), 0.001);

        // Sample variance: ((10-30)^2 + (20-30)^2 + (30-30)^2 + (40-30)^2 + (50-30)^2) / 4 = 1000 / 4 = 250
        // stdDev: sqrt(250) ≈ 15.811
        assertEquals(Math.sqrt(250.0), stats.stdDev(), 0.001);
        assertEquals((Math.sqrt(250.0) / 30.0) * 100.0, stats.coefficientOfVariation(), 0.001);

        assertTrue(stats.p95() > 40.0);
        assertTrue(stats.p99() > 45.0);

        String summary = stats.formatSummary("Mbps");
        assertTrue(summary.contains("Mean: 30.00 Mbps"));
        assertTrue(summary.contains("Median: 30.00 Mbps"));
    }

    @Test
    public void testPercentileEdgeCases()
    {
        List<Double> values = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        assertEquals(10.0, BenchmarkStats.percentile(values, 0.0));
        assertEquals(50.0, BenchmarkStats.percentile(values, 100.0));
        assertEquals(30.0, BenchmarkStats.percentile(values, 50.0));
    }
}
