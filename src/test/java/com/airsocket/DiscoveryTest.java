package com.airsocket;

import com.airsocket.discovery.Discoverer;
import com.airsocket.discovery.Responder;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DiscoveryTest
{
    @Test
    public void testDiscovery() throws Exception
    {
        int dummyTcpPort = 12345;
        try (Responder responder = new Responder(dummyTcpPort))
        {
            // Allow some time for responder socket to bind and start listening
            Thread.sleep(100);

            List<Peer> peers = Discoverer.scan(Duration.ofMillis(500));
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
