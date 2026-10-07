package com.airsocket;

import com.airsocket.benchmark.NetemSimulator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

public class NetemSimulatorTest
{
    @Test
    public void testNetemLatencySimulation() throws Exception
    {
        int targetPort = 19280;
        int proxyPort = 19281;

        // Simple echo server
        try (ServerSocket targetServer = new ServerSocket(targetPort))
        {
            Thread serverThread = Thread.ofVirtual().start(() ->
            {
                try (Socket socket = targetServer.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream())
                {
                    byte[] buf = new byte[256];
                    int r = in.read(buf);
                    if (r > 0)
                    {
                        out.write(buf, 0, r);
                        out.flush();
                    }
                }
                catch (Exception ignored) {}
            });

            // Start NetemSimulator with 40ms latency
            try (NetemSimulator sim = NetemSimulator.create(proxyPort, "127.0.0.1", targetPort, 40L, 0L, 0.0, 0.0))
            {
                sim.start();
                Thread.sleep(50);

                long startTime = System.currentTimeMillis();
                try (Socket client = new Socket("127.0.0.1", proxyPort);
                     OutputStream out = client.getOutputStream();
                     InputStream in = client.getInputStream())
                {
                    out.write("PingNetem".getBytes());
                    out.flush();

                    byte[] resp = new byte[256];
                    int read = in.read(resp);
                    long elapsed = System.currentTimeMillis() - startTime;

                    assertEquals(9, read);
                    assertEquals("PingNetem", new String(resp, 0, read));
                    // Transmission forward has at least 40ms delay
                    assertTrue(elapsed >= 35, "Latency simulation should add delay (elapsed: " + elapsed + "ms)");
                }
            }

            serverThread.join(1000);
        }
    }
}
