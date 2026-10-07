package com.airsocket.apps;

import com.airsocket.AirSocket;
import com.airsocket.Peer;
import com.airsocket.crypto.Crypto;
import com.airsocket.discovery.Responder;
import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import com.airsocket.benchmark.BenchmarkConfig;
import com.airsocket.benchmark.BenchmarkServer;
import com.airsocket.benchmark.BenchmarkSuite;
import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class Cli
{
    public static void main(String[] args)
    {
        if (args.length == 0 || hasOption(args, "--help"))
        {
            printHelp();
            return;
        }

        String command = args[0];
        String logLevelStr = getStringOption(args, "--log-level", null);
        if (logLevelStr != null)
        {
            com.airsocket.logging.Logger.setGlobalLevel(com.airsocket.logging.LogLevel.fromString(logLevelStr, com.airsocket.logging.LogLevel.INFO));
        }
        else if (hasOption(args, "--debug"))
        {
            com.airsocket.logging.Logger.setGlobalLevel(com.airsocket.logging.LogLevel.DEBUG);
        }
        else if (hasOption(args, "--trace"))
        {
            com.airsocket.logging.Logger.setGlobalLevel(com.airsocket.logging.LogLevel.TRACE);
        }

        int port = getIntOption(args, "--port", 9000);
        if (!isValidPort(port))
        {
            System.out.println("Error: --port must be a value between 1 and 65535.");
            return;
        }

        boolean encrypt = hasOption(args, "--encrypt");
        boolean resume = hasOption(args, "--resume");
        boolean progress = !hasOption(args, "--no-progress");

        char[] passphrase = new char[0];
        try
        {
            if (encrypt)
            {
                passphrase = readPassphrase();
                if (passphrase == null || passphrase.length == 0)
                {
                    System.out.println("Error: A non-empty passphrase is required when --encrypt is used.");
                    return;
                }
            }

            switch (command)
            {
                case "discover":
                    System.out.println("Scanning LAN...");
                    List<Peer> peers = AirSocket.discover(Duration.ofSeconds(2));
                    if (peers.isEmpty())
                    {
                        System.out.println("No peers found.");
                    }
                    else
                    {
                        for (Peer p : peers)
                        {
                            System.out.println(String.format("  %-15s  %-15s  (RTT: %.1fms)",
                                p.addr().getHostAddress(), p.hostname(), (double) p.rttMs()));
                        }
                    }
                    break;

                case "send":
                    if (args.length < 2)
                    {
                        System.out.println("Error: Please specify the file to send.");
                        return;
                    }
                    String filePath = args[1];
                    String targetIp = getStringOption(args, "--to", null);

                    if (hasOption(args, "--benchmark"))
                    {
                        if (targetIp == null)
                        {
                            System.out.println("Error: Benchmarking requires a target IP via --to <ip>");
                            return;
                        }
                        validateHost(targetIp);
                        String bufList = getStringOption(args, "--buf-sizes", "8192,16384,32768,65536,131072,262144");
                        String[] parts = bufList.split(",");
                        int[] bufSizes = new int[parts.length];
                        for (int i = 0; i < parts.length; i++)
                        {
                            int bufSize = Integer.parseInt(parts[i].trim());
                            if (bufSize <= 0)
                            {
                                throw new IllegalArgumentException("Buffer sizes must be greater than zero.");
                            }
                            bufSizes[i] = bufSize;
                        }
                        Sender.runBenchmark(targetIp, port, bufSizes);
                    }
                    else
                    {
                        if (targetIp == null)
                        {
                            System.out.println("Scanning LAN for a peer...");
                            List<Peer> discovered = AirSocket.discover(Duration.ofSeconds(2));
                            if (discovered.isEmpty())
                            {
                                System.out.println("Error: No peers found. Please specify target IP via --to <ip>");
                                return;
                            }
                            Peer p = discovered.get(0);
                            targetIp = p.addr().getHostAddress();
                            port = p.port();
                            System.out.println("Sending to first found peer: " + targetIp + ":" + port);
                        }
                        else
                        {
                            validateHost(targetIp);
                        }

                        File file = new File(filePath);
                        if (!file.exists() || !file.isFile())
                        {
                            System.out.println("Error: File not found: " + filePath);
                            return;
                        }
                        int timeout = getIntOption(args, "--timeout", Sender.DEFAULT_READ_TIMEOUT_MS);
                        int retries = getIntOption(args, "--retries", resume ? Sender.DEFAULT_MAX_RETRIES : 0);
                        int protoVersionNum = getIntOption(args, "--protocol-version", 4);
                        com.airsocket.protocol.ProtocolVersion protoVersion = com.airsocket.protocol.ProtocolVersion.fromInt(protoVersionNum);
                        Sender.sendFile(targetIp, port, file, encrypt, passphrase, resume, progress,
                            protoVersion, Sender.DEFAULT_CONNECT_TIMEOUT_MS, timeout, retries);
                    }
                    break;

                case "receive":
                    String outputDir = getStringOption(args, "--output-dir", null);
                    if (outputDir == null)
                    {
                        outputDir = getStringOption(args, "-o", ".");
                    }
                    int receiveTimeout = getIntOption(args, "--timeout", 15000);
                    Path downloadPath = Path.of(outputDir);
                    try (Responder responder = AirSocket.respond(port);
                         Receiver receiver = new Receiver(port, encrypt, passphrase, resume, downloadPath))
                    {
                        receiver.setSocketTimeoutMs(receiveTimeout);
                        receiver.start();
                    }
                    break;

                case "benchmark":
                    boolean isServer = hasOption(args, "--server");
                    if (isServer)
                    {
                        System.out.println("Starting Benchmark Server on port " + port + "...");
                        try (BenchmarkServer server = new BenchmarkServer(port))
                        {
                            server.start();
                            System.out.println("Benchmark Server running. Press Ctrl+C to stop.");
                            Thread.currentThread().join();
                        }
                    }
                    else
                    {
                        String target = getStringOption(args, "--to", null);
                        int runs = getIntOption(args, "--runs", 3);
                        int sizeMb = getIntOption(args, "--size-mb", 25);
                        long payload = (long) sizeMb * 1024 * 1024;
                        String suiteName = getStringOption(args, "--suite", "all");
                        String benchFilePath = getStringOption(args, "--file", null);
                        File targetFile = benchFilePath != null ? new File(benchFilePath) : null;

                        BenchmarkSuite suite = new BenchmarkSuite(System.out);
                        BenchmarkServer localServer = null;

                        try
                        {
                            if (target == null)
                            {
                                localServer = new BenchmarkServer(port);
                                localServer.start();
                                localServer.awaitReady();
                                target = "127.0.0.1";
                            }
                            else
                            {
                                validateHost(target);
                            }

                            switch (suiteName.toLowerCase())
                            {
                                case "app-vs-tcp":
                                    suite.runAppVsTcpBufferExperiment(target, port, null, null, runs, payload);
                                    break;
                                case "nodelay":
                                    suite.runTcpNoDelayABTest(target, port, null, runs, payload);
                                    break;
                                case "rcvbuf":
                                    suite.runReceiveBufferSweep(target, port, null, runs, payload);
                                    break;
                                case "sweep":
                                    suite.runBufferSweep4KiBTo4MiB(target, port, runs, payload);
                                    break;
                                case "stats":
                                    BenchmarkConfig cfg = BenchmarkConfig.builder()
                                        .host(target)
                                        .port(port)
                                        .payloadBytes(payload)
                                        .iterations(runs)
                                        .warmupRuns(1)
                                        .build();
                                    suite.runStatisticalBenchmark(cfg);
                                    break;
                                case "realfile":
                                    suite.runRealFileBenchmark(target, port, targetFile, runs);
                                    break;
                                case "encrypt-overhead":
                                    suite.runEncryptionOverheadBenchmark(target, port, payload, runs);
                                    break;
                                case "profile":
                                    BenchmarkConfig pCfg = BenchmarkConfig.builder()
                                        .host(target)
                                        .port(port)
                                        .payloadBytes(payload)
                                        .iterations(runs)
                                        .warmupRuns(1)
                                        .build();
                                    suite.runProfiledBenchmark(pCfg);
                                    break;
                                case "netem":
                                    suite.runNetemSimulationBenchmark(target, port, port + 1, runs, Math.min(payload, 10L * 1024 * 1024));
                                    break;
                                case "all":
                                default:
                                    suite.runAppVsTcpBufferExperiment(target, port, null, null, runs, payload);
                                    suite.runTcpNoDelayABTest(target, port, null, runs, payload);
                                    suite.runReceiveBufferSweep(target, port, null, runs, payload);
                                    suite.runBufferSweep4KiBTo4MiB(target, port, runs, payload);
                                    suite.runRealFileBenchmark(target, port, targetFile, runs);
                                    suite.runEncryptionOverheadBenchmark(target, port, payload, runs);
                                    suite.runNetemSimulationBenchmark(target, port, port + 1, runs, Math.min(payload, 10L * 1024 * 1024));
                                    break;
                            }
                        }
                        finally
                        {
                            if (localServer != null)
                            {
                                localServer.close();
                            }
                        }
                    }
                    break;

                case "--help":
                case "-h":
                case "help":
                    printHelp();
                    break;

                default:
                    if (hasOption(args, "--benchmark"))
                    {
                        String targetIpBench = getStringOption(args, "--to", null);
                        if (targetIpBench == null)
                        {
                            System.out.println("Error: Benchmarking requires a target IP via --to <ip>");
                            return;
                        }
                        String bufList = getStringOption(args, "--buf-sizes", "8192,16384,32768,65536,131072,262144");
                        String[] parts = bufList.split(",");
                        int[] bufSizes = new int[parts.length];
                        for (int i = 0; i < parts.length; i++)
                        {
                            bufSizes[i] = Integer.parseInt(parts[i].trim());
                        }
                        Sender.runBenchmark(targetIpBench, port, bufSizes);
                    }
                    else
                    {
                        System.out.println("Unknown command: " + command);
                        printHelp();
                    }
                    break;
            }
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
        finally
        {
            Crypto.wipe(passphrase);
        }
    }

    private static boolean hasOption(String[] args, String opt)
    {
        for (String arg : args)
        {
            if (arg.equals(opt) || arg.startsWith(opt + "="))
            {
                return true;
            }
        }
        return false;
    }

    private static String getStringOption(String[] args, String opt, String defaultValue)
    {
        for (int i = 0; i < args.length; i++)
        {
            if (args[i].equals(opt) && i + 1 < args.length)
            {
                return args[i + 1];
            }
            else if (args[i].startsWith(opt + "="))
            {
                return args[i].substring(opt.length() + 1);
            }
        }
        return defaultValue;
    }

    private static int getIntOption(String[] args, String opt, int defaultValue)
    {
        String val = getStringOption(args, opt, null);
        if (val != null)
        {
            try
            {
                return Integer.parseInt(val);
            }
            catch (NumberFormatException e)
            {
                // Ignore and use default
            }
        }
        return defaultValue;
    }

    private static boolean isValidPort(int port)
    {
        return port > 0 && port <= 65535;
    }

    private static void validateHost(String host)
    {
        try
        {
            InetAddress.getByName(host);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Invalid target host: " + host, e);
        }
    }

    private static char[] readPassphrase()
    {
        Console console = System.console();
        if (console != null)
        {
            char[] chars = console.readPassword("Enter passphrase: ");
            return chars == null ? new char[0] : chars;
        }

        try
        {
            System.out.print("Enter passphrase: ");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();
            return line != null ? line.toCharArray() : new char[0];
        }
        catch (IOException e)
        {
            System.err.println("Unable to read passphrase from stdin.");
            return new char[0];
        }
    }

    private static void printHelp()
    {
        System.out.println("Usage: java -jar target/airsocket-1.0.0.jar [command] [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  discover              Scan LAN for other AirSocket instances");
        System.out.println("  send <file>           Send a file to a peer");
        System.out.println("  receive               Listen for incoming files");
        System.out.println("  benchmark             Run comprehensive networking/performance experiments");
        System.out.println();
        System.out.println("Benchmark Options:");
        System.out.println("  --suite <name>        Suite: all, app-vs-tcp, nodelay, rcvbuf, sweep, stats,");
        System.out.println("                               realfile, encrypt-overhead, profile, netem");
        System.out.println("  --runs <n>            Number of measurement iterations (default: 3)");
        System.out.println("  --size-mb <n>         Payload size in megabytes (default: 25)");
        System.out.println("  --server              Run in standalone benchmark server mode");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar target/airsocket-1.0.0.jar discover");
        System.out.println("  java -jar target/airsocket-1.0.0.jar receive --port 9000");
        System.out.println("  java -jar target/airsocket-1.0.0.jar send report.pdf --to 192.168.1.42 --encrypt");
        System.out.println("  java -jar target/airsocket-1.0.0.jar benchmark --suite sweep --runs 5");
        System.out.println("  java -jar target/airsocket-1.0.0.jar benchmark --suite nodelay");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --to <ip>             Target peer IP (for send/benchmark)");
        System.out.println("  --port <n>            Port (default: 9000)");
        System.out.println("  --encrypt             Encrypt with AES-256-GCM (requires passphrase)");
        System.out.println("  --resume              Resume interrupted transfer");
        System.out.println("  --timeout <ms>        Socket read timeout in milliseconds (default: 15000)");
        System.out.println("  --retries <n>         Max reconnection retries on interrupted transfer (default: 3 with --resume)");
        System.out.println("  --protocol-version <v> Protocol version 1..4 (default: 4)");
        System.out.println("  --log-level <level>   Log level: TRACE, DEBUG, INFO, WARN, ERROR (default: INFO)");
        System.out.println("  --debug               Enable debug-level logging shortcut");
        System.out.println("  --no-progress         Disable progress bar");
        System.out.println("  --benchmark           Run throughput benchmark");
        System.out.println("  --buf-sizes <list>    Comma-separated buffer sizes for benchmark");
        System.out.println("  --output-dir, -o <dir> Target directory to save received files (default: .)");
        System.out.println("  --help                Print this help");
    }
}
