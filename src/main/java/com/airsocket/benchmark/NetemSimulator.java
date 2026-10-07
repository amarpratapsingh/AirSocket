package com.airsocket.benchmark;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetemSimulator implements Closeable
{
    private final int localPort;
    private final String targetHost;
    private final int targetPort;
    private final long latencyMs;
    private final long jitterMs;
    private final double lossRate; // 0.0 to 1.0 (e.g. 0.02 for 2%)
    private final long maxBytesPerSecond; // 0 for unlimited, or e.g. (100 * 1024 * 1024) / 8 for 100 Mbps
    private final Random random = new Random();

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = true;

    public NetemSimulator(int localPort, String targetHost, int targetPort,
                          long latencyMs, long jitterMs, double lossRate, long maxBytesPerSecond) throws IOException
    {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.latencyMs = Math.max(0L, latencyMs);
        this.jitterMs = Math.max(0L, jitterMs);
        this.lossRate = Math.max(0.0, Math.min(1.0, lossRate));
        this.maxBytesPerSecond = Math.max(0L, maxBytesPerSecond);
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new java.net.InetSocketAddress(localPort));
        this.localPort = this.serverSocket.getLocalPort();
    }

    public static NetemSimulator create(int localPort, String targetHost, int targetPort,
                                        long latencyMs, long jitterMs, double lossRate, double rateLimitMbps) throws IOException
    {
        long bytesPerSec = rateLimitMbps > 0.0 ? (long) ((rateLimitMbps * 1_000_000.0) / 8.0) : 0L;
        return new NetemSimulator(localPort, targetHost, targetPort, latencyMs, jitterMs, lossRate, bytesPerSec);
    }

    public void start()
    {
        executor.submit(() ->
        {
            while (running && !serverSocket.isClosed())
            {
                try
                {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleBridge(clientSocket));
                }
                catch (IOException e)
                {
                    break;
                }
            }
        });
    }

    private void handleBridge(Socket clientSocket)
    {
        try (clientSocket;
             Socket targetSocket = new Socket(targetHost, targetPort))
        {
            clientSocket.setTcpNoDelay(true);
            targetSocket.setTcpNoDelay(true);

            Thread t1 = Thread.ofVirtual().start(() -> forwardWithSimulation(clientSocket, targetSocket, true));
            Thread t2 = Thread.ofVirtual().start(() -> forwardWithSimulation(targetSocket, clientSocket, false));

            t1.join();
            t2.join();
        }
        catch (Exception e)
        {
            // Bridge closed
        }
    }

    private void forwardWithSimulation(Socket source, Socket destination, boolean applyShaping)
    {
        try (InputStream in = source.getInputStream();
             OutputStream out = destination.getOutputStream())
        {
            byte[] buffer = new byte[8192];
            long tokenBucket = maxBytesPerSecond > 0 ? maxBytesPerSecond : Long.MAX_VALUE;
            long lastRefillTime = System.nanoTime();

            int read;
            while ((read = in.read(buffer)) != -1)
            {
                if (applyShaping)
                {
                    // 1. Packet Loss Simulation
                    if (lossRate > 0.0 && random.nextDouble() < lossRate)
                    {
                        // Simulate dropped chunk/packet: skip writing this block
                        continue;
                    }

                    // 2. Latency & Jitter Simulation
                    if (latencyMs > 0)
                    {
                        long jitterOffset = jitterMs > 0 ? (long) ((random.nextDouble() * 2.0 - 1.0) * jitterMs) : 0L;
                        long sleepTime = Math.max(0L, latencyMs + jitterOffset);
                        if (sleepTime > 0)
                        {
                            Thread.sleep(sleepTime);
                        }
                    }

                    // 3. Bandwidth Rate Limiter (Token Bucket)
                    if (maxBytesPerSecond > 0)
                    {
                        long now = System.nanoTime();
                        long elapsedNanos = now - lastRefillTime;
                        if (elapsedNanos > 0)
                        {
                            long tokensToAdd = (elapsedNanos * maxBytesPerSecond) / 1_000_000_000L;
                            tokenBucket = Math.min(maxBytesPerSecond, tokenBucket + tokensToAdd);
                            lastRefillTime = now;
                        }

                        while (tokenBucket < read)
                        {
                            long deficit = read - tokenBucket;
                            long waitNanos = (deficit * 1_000_000_000L) / maxBytesPerSecond;
                            long waitMs = Math.max(1L, waitNanos / 1_000_000L);
                            Thread.sleep(waitMs);
                            now = System.nanoTime();
                            long added = ((now - lastRefillTime) * maxBytesPerSecond) / 1_000_000_000L;
                            tokenBucket = Math.min(maxBytesPerSecond, tokenBucket + added);
                            lastRefillTime = now;
                        }
                        tokenBucket -= read;
                    }
                }

                out.write(buffer, 0, read);
                out.flush();
            }
        }
        catch (Exception e)
        {
            // Stream disconnected
        }
    }

    public int getLocalPort()
    {
        return localPort;
    }

    @Override
    public void close()
    {
        running = false;
        try
        {
            serverSocket.close();
        }
        catch (IOException ignored) {}
        executor.shutdownNow();
    }
}
