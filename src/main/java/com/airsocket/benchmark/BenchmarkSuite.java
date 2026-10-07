package com.airsocket.benchmark;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

public class BenchmarkSuite
{
    private final PrintStream out;

    public BenchmarkSuite()
    {
        this(System.out);
    }

    public BenchmarkSuite(PrintStream out)
    {
        this.out = out != null ? out : System.out;
    }

    public Map<String, BenchmarkResult> runAppVsTcpBufferExperiment(
        String host, int port, int[] appSizes, int[] tcpSizes, int iterations, long payloadBytes) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("    Application Buffer vs TCP Buffer Experiments (Throughput Matrix)");
        out.println("==========================================================================");

        if (appSizes == null || appSizes.length == 0)
        {
            appSizes = new int[]{4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024};
        }
        if (tcpSizes == null || tcpSizes.length == 0)
        {
            tcpSizes = new int[]{4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024};
        }

        Map<String, BenchmarkResult> results = new LinkedHashMap<>();

        // Print table header
        out.print(String.format("%-15s", "App \\ TCP Buf"));
        for (int tcp : tcpSizes)
        {
            out.print(String.format("%15s", formatBytes(tcp)));
        }
        out.println();
        out.println("-".repeat(15 + 15 * tcpSizes.length));

        for (int app : appSizes)
        {
            out.print(String.format("%-15s", formatBytes(app)));
            for (int tcp : tcpSizes)
            {
                BenchmarkConfig config = BenchmarkConfig.builder()
                    .host(host)
                    .port(port)
                    .payloadBytes(payloadBytes)
                    .appBufferSize(app)
                    .tcpSendBufferSize(tcp)
                    .tcpNoDelay(true)
                    .iterations(iterations)
                    .warmupRuns(1)
                    .build();

                BenchmarkResult res = BenchmarkClient.run(config);
                String key = "app=" + app + ",tcp=" + tcp;
                results.put(key, res);

                out.print(String.format("%12.1f Mbps", res.throughputStats().mean()));
            }
            out.println();
        }

        return results;
    }

    public Map<String, Double> runTcpNoDelayABTest(
        String host, int port, int[] appBufferSizes, int iterations, long payloadBytes) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("          TCP_NODELAY A/B Test (Nagle Algorithm Impact)");
        out.println("==========================================================================");

        if (appBufferSizes == null || appBufferSizes.length == 0)
        {
            appBufferSizes = new int[]{1024, 4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024};
        }

        Map<String, Double> summary = new LinkedHashMap<>();

        out.println(String.format("%-14s | %-16s | %-16s | %-12s",
            "App Buffer", "NoDelay=ON (Mbps)", "NoDelay=OFF (Mbps)", "Speedup"));
        out.println("-".repeat(68));

        for (int appBuf : appBufferSizes)
        {
            // Run with TCP_NODELAY = true
            BenchmarkConfig onConfig = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .payloadBytes(payloadBytes)
                .appBufferSize(appBuf)
                .tcpNoDelay(true)
                .iterations(iterations)
                .warmupRuns(1)
                .build();
            BenchmarkResult onRes = BenchmarkClient.run(onConfig);

            // Run with TCP_NODELAY = false
            BenchmarkConfig offConfig = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .payloadBytes(payloadBytes)
                .appBufferSize(appBuf)
                .tcpNoDelay(false)
                .iterations(iterations)
                .warmupRuns(1)
                .build();
            BenchmarkResult offRes = BenchmarkClient.run(offConfig);

            double onMbps = onRes.throughputStats().mean();
            double offMbps = offRes.throughputStats().mean();
            double speedupPercent = offMbps > 0 ? ((onMbps - offMbps) / offMbps) * 100.0 : 0.0;

            summary.put(appBuf + "_ON", onMbps);
            summary.put(appBuf + "_OFF", offMbps);

            out.println(String.format("%-14s | %13.1f Mbps | %13.1f Mbps | %+10.1f%%",
                formatBytes(appBuf), onMbps, offMbps, speedupPercent));
        }

        return summary;
    }

    public Map<Integer, BenchmarkResult> runReceiveBufferSweep(
        String host, int port, int[] rcvBufferSizes, int iterations, long payloadBytes) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("               Receive-Buffer Tuning (SO_RCVBUF Sweep)");
        out.println("==========================================================================");

        if (rcvBufferSizes == null || rcvBufferSizes.length == 0)
        {
            rcvBufferSizes = new int[]{
                8 * 1024, 32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024, 4 * 1024 * 1024
            };
        }

        Map<Integer, BenchmarkResult> results = new LinkedHashMap<>();

        out.println(String.format("%-16s | %-16s | %-12s | %-14s",
            "SO_RCVBUF", "Mean Throughput", "StdDev", "Avg Duration"));
        out.println("-".repeat(66));

        for (int rcvBuf : rcvBufferSizes)
        {
            BenchmarkConfig config = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .payloadBytes(payloadBytes)
                .appBufferSize(64 * 1024)
                .tcpReceiveBufferSize(rcvBuf)
                .tcpNoDelay(true)
                .iterations(iterations)
                .warmupRuns(1)
                .build();

            BenchmarkResult res = BenchmarkClient.run(config);
            results.put(rcvBuf, res);

            out.println(String.format("%-16s | %13.1f Mbps | %9.2f Mbps | %11.1f ms",
                formatBytes(rcvBuf),
                res.throughputStats().mean(),
                res.throughputStats().stdDev(),
                res.durationStats().mean()
            ));
        }

        return results;
    }

    public Map<Integer, BenchmarkResult> runBufferSweep4KiBTo4MiB(
        String host, int port, int iterations, long payloadBytes) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("                Buffer Scaling Sweep: 4 KiB → 4 MiB");
        out.println("==========================================================================");

        int[] bufferSizes = new int[]{
            4 * 1024,       // 4 KiB
            8 * 1024,       // 8 KiB
            16 * 1024,      // 16 KiB
            32 * 1024,      // 32 KiB
            64 * 1024,      // 64 KiB
            128 * 1024,     // 128 KiB
            256 * 1024,     // 256 KiB
            512 * 1024,     // 512 KiB
            1024 * 1024,    // 1 MiB
            2048 * 1024,    // 2 MiB
            4096 * 1024     // 4 MiB
        };

        Map<Integer, BenchmarkResult> results = new LinkedHashMap<>();

        out.println(String.format("%-12s | %-16s | %-14s | %-12s | %-12s",
            "Buffer Size", "Mean (Mbps)", "Median (Mbps)", "Min (Mbps)", "Max (Mbps)"));
        out.println("-".repeat(76));

        for (int buf : bufferSizes)
        {
            BenchmarkConfig config = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .payloadBytes(payloadBytes)
                .appBufferSize(buf)
                .tcpSendBufferSize(buf)
                .tcpNoDelay(true)
                .iterations(iterations)
                .warmupRuns(1)
                .build();

            BenchmarkResult res = BenchmarkClient.run(config);
            results.put(buf, res);

            BenchmarkStats s = res.throughputStats();
            out.println(String.format("%-12s | %13.1f Mbps | %11.1f Mbps | %9.1f Mbps | %9.1f Mbps",
                formatBytes(buf), s.mean(), s.median(), s.min(), s.max()));
        }

        return results;
    }

    public BenchmarkResult runStatisticalBenchmark(BenchmarkConfig config) throws Exception
    {
        out.println("\n==========================================================================");
        out.printf("            Statistical Benchmark (%d Runs, Payload: %s)%n",
            config.iterations(), formatBytes(config.payloadBytes()));
        out.println("==========================================================================");

        BenchmarkResult res = BenchmarkClient.run(config);

        out.println("Samples (Mbps): " + res.throughputSamplesMbps());
        out.println("Samples (ms):   " + res.durationSamplesMs());
        out.println();
        out.println("Throughput Statistics:");
        out.println(res.throughputStats().formatSummary("Mbps"));
        out.println();
        out.println("Latency/Duration Statistics:");
        out.println(res.durationStats().formatSummary("ms"));
        out.println();

        return res;
    }

    public Map<String, BenchmarkResult> runRealFileBenchmark(
        String host, int port, File file, int iterations) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("             Real-File I/O Benchmark vs In-Memory Baseline");
        out.println("==========================================================================");

        boolean cleanupTempFile = false;
        if (file == null || !file.exists())
        {
            file = File.createTempFile("airsocket_realfile_bench_", ".bin");
            file.deleteOnExit();
            cleanupTempFile = true;
            byte[] dummy = new byte[64 * 1024];
            new SecureRandom().nextBytes(dummy);
            long targetSize = 25L * 1024 * 1024;
            try (FileOutputStream fos = new FileOutputStream(file))
            {
                long written = 0;
                while (written < targetSize)
                {
                    int toWrite = (int) Math.min((long) dummy.length, targetSize - written);
                    fos.write(dummy, 0, toWrite);
                    written += toWrite;
                }
            }
        }

        Map<String, BenchmarkResult> map = new LinkedHashMap<>();
        try
        {
            // Mode 1: Memory-to-Memory (Network Baseline)
            BenchmarkConfig memToMem = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .payloadBytes(file.length())
                .appBufferSize(64 * 1024)
                .receiverSavesToDisk(false)
                .iterations(iterations)
                .warmupRuns(1)
                .build();
            BenchmarkResult resMemMem = BenchmarkClient.run(memToMem);
            map.put("Memory-to-Memory", resMemMem);

            // Mode 2: Disk-to-Memory (Disk Read + Network)
            BenchmarkConfig diskToMem = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .realSourceFile(file)
                .appBufferSize(64 * 1024)
                .receiverSavesToDisk(false)
                .iterations(iterations)
                .warmupRuns(1)
                .build();
            BenchmarkResult resDiskMem = BenchmarkClient.run(diskToMem);
            map.put("Disk-to-Memory", resDiskMem);

            // Mode 3: Disk-to-Disk (Full End-to-End File Transfer)
            BenchmarkConfig diskToDisk = BenchmarkConfig.builder()
                .host(host)
                .port(port)
                .realSourceFile(file)
                .appBufferSize(64 * 1024)
                .receiverSavesToDisk(true)
                .iterations(iterations)
                .warmupRuns(1)
                .build();
            BenchmarkResult resDiskDisk = BenchmarkClient.run(diskToDisk);
            map.put("Disk-to-Disk", resDiskDisk);

            out.println(String.format("%-20s | %-16s | %-14s", "Benchmark Mode", "Mean Throughput", "Avg Duration"));
            out.println("-".repeat(56));
            out.println(String.format("%-20s | %13.1f Mbps | %11.1f ms", "Memory-to-Memory", resMemMem.throughputStats().mean(), resMemMem.durationStats().mean()));
            out.println(String.format("%-20s | %13.1f Mbps | %11.1f ms", "Disk-to-Memory",   resDiskMem.throughputStats().mean(), resDiskMem.durationStats().mean()));
            out.println(String.format("%-20s | %13.1f Mbps | %11.1f ms", "Disk-to-Disk",     resDiskDisk.throughputStats().mean(), resDiskDisk.durationStats().mean()));
        }
        finally
        {
            if (cleanupTempFile && file.exists())
            {
                file.delete();
            }
        }

        return map;
    }

    public Map<String, BenchmarkResult> runEncryptionOverheadBenchmark(
        String host, int port, long payloadBytes, int iterations) throws Exception
    {
        return runEncryptionOverheadBenchmark(host, port, payloadBytes, iterations, "bench-passphrase");
    }

    public Map<String, BenchmarkResult> runEncryptionOverheadBenchmark(
        String host, int port, long payloadBytes, int iterations, String passphrase) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("     Encryption-Overhead Benchmark (Plaintext vs AES-256-GCM)");
        out.println("==========================================================================");

        // 1. Plaintext benchmark
        BenchmarkConfig plainConfig = BenchmarkConfig.builder()
            .host(host)
            .port(port)
            .payloadBytes(payloadBytes)
            .appBufferSize(64 * 1024)
            .encrypted(false)
            .iterations(iterations)
            .warmupRuns(1)
            .build();
        BenchmarkResult plainRes = BenchmarkClient.run(plainConfig);

        // 2. Encrypted benchmark
        BenchmarkConfig encConfig = BenchmarkConfig.builder()
            .host(host)
            .port(port)
            .payloadBytes(payloadBytes)
            .appBufferSize(64 * 1024)
            .encrypted(true)
            .passphrase(passphrase != null ? passphrase : "bench-passphrase")
            .iterations(iterations)
            .warmupRuns(1)
            .build();
        BenchmarkResult encRes = BenchmarkClient.run(encConfig);

        double plainMbps = plainRes.throughputStats().mean();
        double encMbps = encRes.throughputStats().mean();
        double mbpsDrop = plainMbps - encMbps;
        double penaltyPercent = plainMbps > 0 ? (mbpsDrop / plainMbps) * 100.0 : 0.0;

        out.println(String.format("%-24s | %-16s | %-14s", "Cipher Mode", "Throughput (Mbps)", "Avg Duration (ms)"));
        out.println("-".repeat(60));
        out.println(String.format("%-24s | %13.1f Mbps | %11.1f ms", "Plaintext (Zero-Copy)", plainMbps, plainRes.durationStats().mean()));
        out.println(String.format("%-24s | %13.1f Mbps | %11.1f ms", "AES-256-GCM (AEAD Chunk)", encMbps, encRes.durationStats().mean()));
        out.println("-".repeat(60));
        out.printf("  Encryption Penalty: -%.1f Mbps (-%.1f%% overhead)%n", mbpsDrop, penaltyPercent);

        Map<String, BenchmarkResult> map = new LinkedHashMap<>();
        map.put("Plaintext", plainRes);
        map.put("AES-256-GCM", encRes);
        return map;
    }


    public BenchmarkResult runProfiledBenchmark(BenchmarkConfig config) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("                CPU / Disk / Network Profiling Benchmark");
        out.println("==========================================================================");

        BenchmarkResult res = BenchmarkClient.run(config);
        out.println(res.formatSummary());
        return res;
    }

    public Map<String, BenchmarkResult> runNetemSimulationBenchmark(
        String host, int serverPort, int simProxyPort, int iterations, long payloadBytes) throws Exception
    {
        out.println("\n==========================================================================");
        out.println("    Network Impairment Simulation (Netem RTT / Jitter / Loss / Rate)");
        out.println("==========================================================================");

        Map<String, BenchmarkResult> results = new LinkedHashMap<>();

        // Baseline (no impairment)
        BenchmarkConfig baselineConfig = BenchmarkConfig.builder()
            .host(host)
            .port(serverPort)
            .payloadBytes(payloadBytes)
            .iterations(iterations)
            .warmupRuns(0)
            .build();
        BenchmarkResult baseRes = BenchmarkClient.run(baselineConfig);
        results.put("Baseline (Direct)", baseRes);

        // Latency simulation: 20ms RTT
        try (NetemSimulator sim = NetemSimulator.create(0, host, serverPort, 20L, 0L, 0.0, 0.0))
        {
            sim.start();
            Thread.sleep(50);
            BenchmarkConfig config = BenchmarkConfig.builder()
                .host("127.0.0.1")
                .port(sim.getLocalPort())
                .payloadBytes(payloadBytes)
                .iterations(iterations)
                .warmupRuns(0)
                .build();
            results.put("20ms Latency", BenchmarkClient.run(config));
        }

        // Latency + Jitter simulation: 20ms ± 5ms
        try (NetemSimulator sim = NetemSimulator.create(0, host, serverPort, 20L, 5L, 0.0, 0.0))
        {
            sim.start();
            Thread.sleep(50);
            BenchmarkConfig config = BenchmarkConfig.builder()
                .host("127.0.0.1")
                .port(sim.getLocalPort())
                .payloadBytes(payloadBytes)
                .iterations(iterations)
                .warmupRuns(0)
                .build();
            results.put("20ms Latency ± 5ms Jitter", BenchmarkClient.run(config));
        }

        // Bandwidth Throttling: 50 Mbps
        try (NetemSimulator sim = NetemSimulator.create(0, host, serverPort, 0L, 0L, 0.0, 50.0))
        {
            sim.start();
            Thread.sleep(50);
            BenchmarkConfig config = BenchmarkConfig.builder()
                .host("127.0.0.1")
                .port(sim.getLocalPort())
                .payloadBytes(payloadBytes)
                .iterations(iterations)
                .warmupRuns(0)
                .build();
            results.put("50 Mbps Rate Limit", BenchmarkClient.run(config));
        }

        out.println(String.format("%-30s | %-16s | %-14s", "Simulation Profile", "Mean Throughput", "Avg Duration"));
        out.println("-".repeat(66));
        for (Map.Entry<String, BenchmarkResult> entry : results.entrySet())
        {
            BenchmarkResult r = entry.getValue();
            out.println(String.format("%-30s | %13.1f Mbps | %11.1f ms",
                entry.getKey(), r.throughputStats().mean(), r.durationStats().mean()));
        }

        return results;
    }

    private static String formatBytes(long bytes)
    {
        if (bytes >= 1024 * 1024)
        {
            return (bytes / (1024 * 1024)) + " MiB";
        }
        if (bytes >= 1024)
        {
            return (bytes / 1024) + " KiB";
        }
        return bytes + " B";
    }
}
