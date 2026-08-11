package com.airsocket.apps;

import com.airsocket.AirSocket;
import com.airsocket.Peer;
import com.airsocket.discovery.Responder;
import com.airsocket.transfer.Receiver;
import com.airsocket.transfer.Sender;
import java.io.Console;
import java.io.File;
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
        int port = getIntOption(args, "--port", 9000);
        boolean encrypt = hasOption(args, "--encrypt");
        boolean resume = hasOption(args, "--resume");
        boolean progress = !hasOption(args, "--no-progress");

        String passphrase = "";
        if (encrypt)
        {
            Console console = System.console();
            if (console != null)
            {
                char[] chars = console.readPassword("Enter passphrase: ");
                passphrase = new String(chars);
            }
            else
            {
                passphrase = "default-passphrase";
            }
        }

        try
        {
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
                        String bufList = getStringOption(args, "--buf-sizes", "8192,16384,32768,65536,131072,262144");
                        String[] parts = bufList.split(",");
                        int[] bufSizes = new int[parts.length];
                        for (int i = 0; i < parts.length; i++)
                        {
                            bufSizes[i] = Integer.parseInt(parts[i].trim());
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

                        File file = new File(filePath);
                        Sender.sendFile(targetIp, port, file, encrypt, passphrase, resume, progress);
                    }
                    break;

                case "receive":
                    try (Responder responder = AirSocket.respond(port))
                    {
                        Receiver receiver = new Receiver(port, encrypt, passphrase, resume);
                        receiver.start();
                    }
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

    private static void printHelp()
    {
        System.out.println("Usage: airsocket [command] [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  discover              Scan LAN for other AirSocket instances");
        System.out.println("  send <file>           Send a file to a peer");
        System.out.println("  receive               Listen for incoming files");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --to <ip>             Target peer IP (for send/benchmark)");
        System.out.println("  --port <n>            Port (default: 9000)");
        System.out.println("  --encrypt             Encrypt with AES-256-GCM (prompts for passphrase)");
        System.out.println("  --resume              Resume interrupted transfer");
        System.out.println("  --no-progress         Disable progress bar");
        System.out.println("  --benchmark           Run throughput benchmark");
        System.out.println("  --buf-sizes <list>    Comma-separated buffer sizes for benchmark");
        System.out.println("  --help                Print this help");
    }
}
