package com.airsocket.benchmark;

import com.airsocket.crypto.Crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkClient
{
    public static class SingleRunResult
    {
        public final double throughputMbps;
        public final long durationMs;

        public SingleRunResult(double throughputMbps, long durationMs)
        {
            this.throughputMbps = throughputMbps;
            this.durationMs = durationMs;
        }
    }

    public static BenchmarkResult run(BenchmarkConfig config) throws Exception
    {
        // 1. Warm-up iterations
        for (int i = 0; i < config.warmupRuns(); i++)
        {
            executeSingleRun(config);
            Thread.sleep(50);
        }

        // 2. Profile snapshot before measurement iterations
        ProfileSnapshot startProfile = SystemProfiler.takeSnapshot();

        List<Double> throughputSamples = new ArrayList<>();
        List<Long> durationSamples = new ArrayList<>();
        long totalBytesMeasured = 0L;

        for (int i = 0; i < config.iterations(); i++)
        {
            SingleRunResult run = executeSingleRun(config);
            throughputSamples.add(run.throughputMbps);
            durationSamples.add(run.durationMs);
            totalBytesMeasured += config.payloadBytes();
            if (i < config.iterations() - 1)
            {
                Thread.sleep(50);
            }
        }

        // 3. Profile snapshot after measurement iterations
        ProfileSnapshot endProfile = SystemProfiler.takeSnapshot();

        BenchmarkStats throughputStats = BenchmarkStats.compute(throughputSamples);
        List<Double> durationDoubles = new ArrayList<>();
        for (Long d : durationSamples)
        {
            durationDoubles.add(d.doubleValue());
        }
        BenchmarkStats durationStats = BenchmarkStats.compute(durationDoubles);
        SystemProfiler.SystemProfileDiff profileDiff = SystemProfiler.diff(startProfile, endProfile, totalBytesMeasured);

        return new BenchmarkResult(
            config,
            throughputSamples,
            durationSamples,
            throughputStats,
            durationStats,
            profileDiff
        );
    }

    public static SingleRunResult executeSingleRun(BenchmarkConfig config) throws Exception
    {
        try (Socket socket = new Socket(config.host(), config.port()))
        {
            socket.setTcpNoDelay(config.tcpNoDelay());
            if (config.tcpSendBufferSize() > 0)
            {
                socket.setSendBufferSize(config.tcpSendBufferSize());
            }
            if (config.tcpReceiveBufferSize() > 0)
            {
                socket.setReceiveBufferSize(config.tcpReceiveBufferSize());
            }

            try (OutputStream out = socket.getOutputStream();
                 DataOutputStream dataOut = new DataOutputStream(out);
                 InputStream in = socket.getInputStream();
                 DataInputStream dataIn = new DataInputStream(in))
            {
                // Prepare crypto if enabled
                byte[] salt = new byte[Crypto.SALT_LENGTH];
                byte[] ivMeta = new byte[Crypto.IV_LENGTH];
                byte[] ivData = new byte[Crypto.IV_LENGTH];
                SecretKeySpec secretKey = null;

                if (config.encrypted())
                {
                    new SecureRandom().nextBytes(salt);
                    new SecureRandom().nextBytes(ivMeta);
                    new SecureRandom().nextBytes(ivData);
                    secretKey = Crypto.deriveKey(config.passphrase().toCharArray(), salt);
                }

                // Send Extended Benchmark Handshake
                dataOut.writeInt(0x41525354); // ARST
                dataOut.writeByte(0xBE);      // Benchmark message
                int flags = 0;
                if (config.encrypted()) flags |= 0x01;
                if (config.receiverSavesToDisk()) flags |= 0x02;
                if (config.tcpNoDelay()) flags |= 0x04;
                dataOut.writeByte(flags);

                dataOut.writeLong(config.payloadBytes());
                dataOut.writeInt(config.appBufferSize());
                dataOut.writeInt(config.tcpReceiveBufferSize());

                if (config.encrypted())
                {
                    dataOut.write(salt);
                    dataOut.write(ivMeta);
                    dataOut.write(ivData);
                }
                dataOut.flush();

                // Read Handshake ACK
                int ack = dataIn.read();
                if (ack != 0x06)
                {
                    throw new IOException("Benchmark receiver failed to ACK handshake (code: " + ack + ")");
                }

                long totalBytes = config.payloadBytes();
                int appBufSize = config.appBufferSize();
                byte[] memoryBuffer = new byte[appBufSize];
                new SecureRandom().nextBytes(memoryBuffer);

                File realFile = config.realSourceFile();
                FileInputStream fis = realFile != null ? new FileInputStream(realFile) : null;

                long startTime = System.nanoTime();
                long bytesSent = 0;
                long chunkIndex = 0;

                try
                {
                    if (config.encrypted() && secretKey != null)
                    {
                        while (bytesSent < totalBytes)
                        {
                            int toRead = (int) Math.min((long) appBufSize, totalBytes - bytesSent);
                            byte[] chunkPlain;
                            if (fis != null)
                            {
                                chunkPlain = new byte[toRead];
                                int r = fis.read(chunkPlain);
                                if (r == -1) break;
                            }
                            else
                            {
                                chunkPlain = toRead == memoryBuffer.length ? memoryBuffer : java.util.Arrays.copyOf(memoryBuffer, toRead);
                            }

                            byte[] chunkIv = Crypto.deriveChunkIv(ivData, chunkIndex);
                            Cipher chunkCipher = Crypto.getCipher(secretKey, chunkIv, Cipher.ENCRYPT_MODE);
                            byte[] encryptedChunk = chunkCipher.doFinal(chunkPlain);

                            dataOut.writeInt(encryptedChunk.length);
                            dataOut.write(encryptedChunk);

                            bytesSent += toRead;
                            chunkIndex++;
                        }
                    }
                    else
                    {
                        while (bytesSent < totalBytes)
                        {
                            int toWrite = (int) Math.min((long) appBufSize, totalBytes - bytesSent);
                            if (fis != null)
                            {
                                int r = fis.read(memoryBuffer, 0, toWrite);
                                if (r == -1) break;
                                out.write(memoryBuffer, 0, r);
                                bytesSent += r;
                            }
                            else
                            {
                                out.write(memoryBuffer, 0, toWrite);
                                bytesSent += toWrite;
                            }
                        }
                    }
                    out.flush();

                    // Read receiver completion confirmation
                    int completionAck = dataIn.read();
                    if (completionAck != 0x06)
                    {
                        throw new IOException("Benchmark receiver failed to confirm completion (code: " + completionAck + ")");
                    }
                }
                finally
                {
                    if (fis != null)
                    {
                        fis.close();
                    }
                }

                long endTime = System.nanoTime();
                double elapsedSec = Math.max(1L, endTime - startTime) / 1_000_000_000.0;
                long durationMs = (endTime - startTime) / 1_000_000L;
                double mbps = (bytesSent * 8.0) / (elapsedSec * 1_000_000.0);

                return new SingleRunResult(mbps, durationMs);
            }
        }
    }
}
