package com.airsocket.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BenchmarkStats
{
    private final int count;
    private final double mean;
    private final double median;
    private final double min;
    private final double max;
    private final double stdDev;
    private final double p95;
    private final double p99;
    private final double coefficientOfVariation;

    public BenchmarkStats(int count, double mean, double median, double min, double max,
                          double stdDev, double p95, double p99, double coefficientOfVariation)
    {
        this.count = count;
        this.mean = mean;
        this.median = median;
        this.min = min;
        this.max = max;
        this.stdDev = stdDev;
        this.p95 = p95;
        this.p99 = p99;
        this.coefficientOfVariation = coefficientOfVariation;
    }

    public static BenchmarkStats compute(List<Double> values)
    {
        if (values == null || values.isEmpty())
        {
            return new BenchmarkStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();

        double sum = 0.0;
        double min = sorted.get(0);
        double max = sorted.get(n - 1);

        for (double v : sorted)
        {
            sum += v;
        }
        double mean = sum / n;

        double median = percentile(sorted, 50.0);
        double p95 = percentile(sorted, 95.0);
        double p99 = percentile(sorted, 99.0);

        double varianceSum = 0.0;
        for (double v : sorted)
        {
            varianceSum += (v - mean) * (v - mean);
        }
        double stdDev = n > 1 ? Math.sqrt(varianceSum / (n - 1)) : 0.0;
        double cv = mean > 0.0 ? (stdDev / mean) * 100.0 : 0.0;

        return new BenchmarkStats(n, mean, median, min, max, stdDev, p95, p99, cv);
    }

    public static double percentile(List<Double> sorted, double percentile)
    {
        if (sorted.isEmpty())
        {
            return 0.0;
        }
        if (sorted.size() == 1)
        {
            return sorted.get(0);
        }
        if (percentile <= 0.0)
        {
            return sorted.get(0);
        }
        if (percentile >= 100.0)
        {
            return sorted.get(sorted.size() - 1);
        }

        double rank = (percentile / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high)
        {
            return sorted.get(low);
        }
        double weight = rank - low;
        return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
    }

    public int count()
    {
        return count;
    }

    public double mean()
    {
        return mean;
    }

    public double median()
    {
        return median;
    }

    public double min()
    {
        return min;
    }

    public double max()
    {
        return max;
    }

    public double stdDev()
    {
        return stdDev;
    }

    public double p95()
    {
        return p95;
    }

    public double p99()
    {
        return p99;
    }

    public double coefficientOfVariation()
    {
        return coefficientOfVariation;
    }

    public String formatSummary(String unit)
    {
        return String.format(
            "N=%d | Mean: %.2f %s (±%.2f, CV: %.1f%%) | Median: %.2f %s | Min: %.2f | Max: %.2f | P95: %.2f | P99: %.2f",
            count, mean, unit, stdDev, coefficientOfVariation, median, unit, min, max, p95, p99
        );
    }
}
