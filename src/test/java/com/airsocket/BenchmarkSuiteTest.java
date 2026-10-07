package com.airsocket;

import com.airsocket.benchmark.BenchmarkConfig;
import com.airsocket.benchmark.BenchmarkResult;
import com.airsocket.benchmark.BenchmarkServer;
import com.airsocket.benchmark.BenchmarkSuite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BenchmarkSuiteTest
{
    private static BenchmarkServer server;
    private static final int SERVER_PORT = 19290;
    private static final int NETEM_PROXY_PORT = 19291;
    private static final long TEST_PAYLOAD_BYTES = 2L * 1024 * 1024; // 2 MB for fast, reliable CI tests

    @TempDir
    static Path tempDir;

    @BeforeAll
    public static void setUp() throws Exception
    {
        server = new BenchmarkServer(SERVER_PORT, 256 * 1024, tempDir, "bench-test-passphrase");
        server.start();
        server.awaitReady();
    }

    @AfterAll
    public static void tearDown()
    {
        if (server != null)
        {
            server.close();
        }
    }

    @Test
    public void test14_AppVsTcpBufferExperiment() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        int[] appSizes = new int[]{16 * 1024, 64 * 1024};
        int[] tcpSizes = new int[]{16 * 1024, 64 * 1024};

        Map<String, BenchmarkResult> results = suite.runAppVsTcpBufferExperiment(
            "127.0.0.1", SERVER_PORT, appSizes, tcpSizes, 1, TEST_PAYLOAD_BYTES);

        assertNotNull(results);
        assertEquals(4, results.size(), "2x2 grid should produce 4 results");
        for (BenchmarkResult res : results.values())
        {
            assertTrue(res.throughputStats().mean() > 0.0, "Throughput should be positive");
        }
    }

    @Test
    public void test15_TcpNoDelayABTest() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        int[] appSizes = new int[]{4 * 1024, 64 * 1024};
        Map<String, Double> abResults = suite.runTcpNoDelayABTest(
            "127.0.0.1", SERVER_PORT, appSizes, 1, TEST_PAYLOAD_BYTES);

        assertNotNull(abResults);
        assertTrue(abResults.containsKey("4096_ON"));
        assertTrue(abResults.containsKey("4096_OFF"));
        assertTrue(abResults.get("4096_ON") > 0.0);
        assertTrue(abResults.get("4096_OFF") > 0.0);
    }

    @Test
    public void test16_ReceiveBufferSweep() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        int[] rcvSizes = new int[]{32 * 1024, 128 * 1024};
        Map<Integer, BenchmarkResult> results = suite.runReceiveBufferSweep(
            "127.0.0.1", SERVER_PORT, rcvSizes, 1, TEST_PAYLOAD_BYTES);

        assertNotNull(results);
        assertEquals(2, results.size());
        for (BenchmarkResult res : results.values())
        {
            assertTrue(res.throughputStats().mean() > 0.0);
        }
    }

    @Test
    public void test17_BufferScalingSweep4KiBTo4MiB() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        Map<Integer, BenchmarkResult> results = suite.runBufferSweep4KiBTo4MiB(
            "127.0.0.1", SERVER_PORT, 1, 1024 * 1024L);

        assertNotNull(results);
        assertEquals(11, results.size(), "4KiB to 4MiB sweep should evaluate 11 power-of-two buffer sizes");
        assertTrue(results.containsKey(4096));
        assertTrue(results.containsKey(4096 * 1024));
    }

    @Test
    public void test18And19_MultipleRunsAndStatisticalAnalysis() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        BenchmarkConfig config = BenchmarkConfig.builder()
            .host("127.0.0.1")
            .port(SERVER_PORT)
            .payloadBytes(TEST_PAYLOAD_BYTES)
            .iterations(3)
            .warmupRuns(1)
            .build();

        BenchmarkResult res = suite.runStatisticalBenchmark(config);
        assertNotNull(res);
        assertEquals(3, res.throughputSamplesMbps().size());
        assertEquals(3, res.throughputStats().count());
        assertTrue(res.throughputStats().mean() > 0.0);
        assertTrue(res.throughputStats().median() > 0.0);
        assertTrue(res.throughputStats().min() > 0.0);
        assertTrue(res.throughputStats().max() >= res.throughputStats().min());
    }

    @Test
    public void test20_RealFileBenchmark() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        File testFile = tempDir.resolve("real_bench_file.bin").toFile();
        try (FileOutputStream fos = new FileOutputStream(testFile))
        {
            fos.write(new byte[1024 * 1024]); // 1 MB
        }

        Map<String, BenchmarkResult> results = suite.runRealFileBenchmark(
            "127.0.0.1", SERVER_PORT, testFile, 1);

        assertNotNull(results);
        assertTrue(results.containsKey("Memory-to-Memory"));
        assertTrue(results.containsKey("Disk-to-Memory"));
        assertTrue(results.containsKey("Disk-to-Disk"));
    }

    @Test
    public void test21_EncryptionOverheadBenchmark() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        Map<String, BenchmarkResult> results = suite.runEncryptionOverheadBenchmark(
            "127.0.0.1", SERVER_PORT, TEST_PAYLOAD_BYTES, 1, "bench-test-passphrase");

        assertNotNull(results);
        assertTrue(results.containsKey("Plaintext"));
        assertTrue(results.containsKey("AES-256-GCM"));

        BenchmarkResult plain = results.get("Plaintext");
        BenchmarkResult enc = results.get("AES-256-GCM");
        assertTrue(plain.throughputStats().mean() > 0.0);
        assertTrue(enc.throughputStats().mean() > 0.0);
    }

    @Test
    public void test22_CpuDiskNetworkProfiling() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        BenchmarkConfig config = BenchmarkConfig.builder()
            .host("127.0.0.1")
            .port(SERVER_PORT)
            .payloadBytes(TEST_PAYLOAD_BYTES)
            .iterations(2)
            .warmupRuns(0)
            .build();

        BenchmarkResult res = suite.runProfiledBenchmark(config);
        assertNotNull(res);
        assertNotNull(res.systemProfile());
        assertTrue(res.systemProfile().elapsedSeconds() > 0.0);
        assertTrue(res.systemProfile().throughputMbps() > 0.0);
    }

    @Test
    public void test23_NetemSimulationBenchmark() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BenchmarkSuite suite = new BenchmarkSuite(new PrintStream(out));

        Map<String, BenchmarkResult> results = suite.runNetemSimulationBenchmark(
            "127.0.0.1", SERVER_PORT, NETEM_PROXY_PORT, 1, 512 * 1024L);

        assertNotNull(results);
        assertTrue(results.containsKey("Baseline (Direct)"));
        assertTrue(results.containsKey("20ms Latency"));
        assertTrue(results.containsKey("20ms Latency ± 5ms Jitter"));
        assertTrue(results.containsKey("50 Mbps Rate Limit"));
    }
}
