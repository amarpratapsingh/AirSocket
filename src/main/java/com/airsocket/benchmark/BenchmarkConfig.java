package com.airsocket.benchmark;

import java.io.File;
import java.nio.file.Path;

public class BenchmarkConfig
{
    private final String host;
    private final int port;
    private final long payloadBytes;
    private final int appBufferSize;
    private final int tcpSendBufferSize;
    private final int tcpReceiveBufferSize;
    private final boolean tcpNoDelay;
    private final boolean encrypted;
    private final String passphrase;
    private final int iterations;
    private final int warmupRuns;
    private final File realSourceFile;
    private final boolean receiverSavesToDisk;
    private final Path outputDir;

    private BenchmarkConfig(Builder b)
    {
        this.host = b.host != null ? b.host : "127.0.0.1";
        this.port = b.port;
        long pBytes = b.payloadBytes;
        if (pBytes <= 0)
        {
            if (b.realSourceFile != null && b.realSourceFile.exists())
            {
                pBytes = b.realSourceFile.length();
            }
            else
            {
                pBytes = 25L * 1024 * 1024;
            }
        }
        else if (b.realSourceFile != null && b.realSourceFile.exists())
        {
            pBytes = Math.min(pBytes, b.realSourceFile.length());
        }
        this.payloadBytes = pBytes;
        this.appBufferSize = b.appBufferSize > 0 ? b.appBufferSize : 64 * 1024;
        this.tcpSendBufferSize = b.tcpSendBufferSize;
        this.tcpReceiveBufferSize = b.tcpReceiveBufferSize;
        this.tcpNoDelay = b.tcpNoDelay;
        this.encrypted = b.encrypted;
        this.passphrase = b.passphrase != null ? b.passphrase : "bench-passphrase";
        this.iterations = Math.max(1, b.iterations);
        this.warmupRuns = Math.max(0, b.warmupRuns);
        this.realSourceFile = b.realSourceFile;
        this.receiverSavesToDisk = b.receiverSavesToDisk;
        this.outputDir = b.outputDir;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private String host = "127.0.0.1";
        private int port = 9000;
        private long payloadBytes = 0L;
        private int appBufferSize = 64 * 1024;
        private int tcpSendBufferSize = 0;
        private int tcpReceiveBufferSize = 0;
        private boolean tcpNoDelay = true;
        private boolean encrypted = false;
        private String passphrase = "bench-passphrase";
        private int iterations = 3;
        private int warmupRuns = 1;
        private File realSourceFile = null;
        private boolean receiverSavesToDisk = false;
        private Path outputDir = null;

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder payloadBytes(long bytes) { this.payloadBytes = bytes; return this; }
        public Builder appBufferSize(int size) { this.appBufferSize = size; return this; }
        public Builder tcpSendBufferSize(int size) { this.tcpSendBufferSize = size; return this; }
        public Builder tcpReceiveBufferSize(int size) { this.tcpReceiveBufferSize = size; return this; }
        public Builder tcpNoDelay(boolean noDelay) { this.tcpNoDelay = noDelay; return this; }
        public Builder encrypted(boolean enc) { this.encrypted = enc; return this; }
        public Builder passphrase(String pass) { this.passphrase = pass; return this; }
        public Builder iterations(int iter) { this.iterations = iter; return this; }
        public Builder warmupRuns(int warmup) { this.warmupRuns = warmup; return this; }
        public Builder realSourceFile(File file) { this.realSourceFile = file; return this; }
        public Builder receiverSavesToDisk(boolean save) { this.receiverSavesToDisk = save; return this; }
        public Builder outputDir(Path dir) { this.outputDir = dir; return this; }

        public BenchmarkConfig build()
        {
            return new BenchmarkConfig(this);
        }
    }

    public String host() { return host; }
    public int port() { return port; }
    public long payloadBytes() { return payloadBytes; }
    public int appBufferSize() { return appBufferSize; }
    public int tcpSendBufferSize() { return tcpSendBufferSize; }
    public int tcpReceiveBufferSize() { return tcpReceiveBufferSize; }
    public boolean tcpNoDelay() { return tcpNoDelay; }
    public boolean encrypted() { return encrypted; }
    public String passphrase() { return passphrase; }
    public int iterations() { return iterations; }
    public int warmupRuns() { return warmupRuns; }
    public File realSourceFile() { return realSourceFile; }
    public boolean receiverSavesToDisk() { return receiverSavesToDisk; }
    public Path outputDir() { return outputDir; }
}
