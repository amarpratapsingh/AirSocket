package com.airsocket;

import com.airsocket.discovery.Discoverer;
import com.airsocket.discovery.Responder;
import org.junit.jupiter.api.Test;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DiscoveryTest
{
    @Test
    public void testDiscoveryIgnoresInvalidPortResponses() throws Exception
    {
        try (DatagramSocket socket = new DatagramSocket())
        {
            socket.setSoTimeout(2000);
            byte[] payload = "{\"type\":\"pong\",\"hostname\":\"bad-peer\",\"port\":0}".getBytes("UTF-8");
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("127.0.0.1"), 42069));

            List<Peer> peers = Discoverer.scan(Duration.ofMillis(300));
            assertTrue(peers.stream().noneMatch(peer -> peer.port() == 0),
                "Discovery should ignore malformed or invalid responses with port 0");
        }
    }

    @Test
    public void testDiscovery() throws Exception
    {
        int dummyTcpPort = 12345;
        try (Responder responder = new Responder(dummyTcpPort))
        {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            List<Peer> peers = List.of();
            do
            {
                peers = Discoverer.scan(Duration.ofMillis(200));
                if (!peers.isEmpty())
                {
                    break;
                }
            }
            while (System.nanoTime() < deadline);

            assertNotNull(peers);
            assertFalse(peers.isEmpty(), "Should discover at least one peer (the local responder)");

            Peer foundPeer = null;
            for (Peer p : peers)
            {
                if (p.port() == dummyTcpPort)
                {
                    foundPeer = p;
                    break;
                }
            }

            assertNotNull(foundPeer, "Should find the peer responding on the dummy TCP port");
            assertTrue(foundPeer.rttMs() >= 0);
            assertNotNull(foundPeer.addr());
            assertNotNull(foundPeer.hostname());
        }
    }
}
