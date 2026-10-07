package com.airsocket.benchmark;

import java.util.List;

public class BenchmarkResult
{
    private final BenchmarkConfig config;
    private final List<Double> throughputSamplesMbps;
    private final List<Long> durationSamplesMs;
    private final BenchmarkStats throughputStats;
    private final BenchmarkStats durationStats;
    private final SystemProfiler.SystemProfileDiff systemProfile;

    public BenchmarkResult(BenchmarkConfig config,
                           List<Double> throughputSamplesMbps,
                           List<Long> durationSamplesMs,
                           BenchmarkStats throughputStats,
                           BenchmarkStats durationStats,
                           SystemProfiler.SystemProfileDiff systemProfile)
    {
        this.config = config;
        this.throughputSamplesMbps = throughputSamplesMbps;
        this.durationSamplesMs = durationSamplesMs;
        this.throughputStats = throughputStats;
        this.durationStats = durationStats;
        this.systemProfile = systemProfile;
    }

    public BenchmarkConfig config()
    {
        return config;
    }

    public List<Double> throughputSamplesMbps()
    {
        return throughputSamplesMbps;
    }

    public List<Long> durationSamplesMs()
    {
        return durationSamplesMs;
    }

    public BenchmarkStats throughputStats()
    {
        return throughputStats;
    }

    public BenchmarkStats durationStats()
    {
        return durationStats;
    }

    public SystemProfiler.SystemProfileDiff systemProfile()
    {
        return systemProfile;
    }

    public String formatSummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Throughput: %s%n", throughputStats.formatSummary("Mbps")));
        sb.append(String.format("Duration:   %s%n", durationStats.formatSummary("ms")));
        if (systemProfile != null)
        {
            sb.append(String.format("Profile:    %s%n", systemProfile.formatReport()));
        }
        return sb.toString();
    }
}
