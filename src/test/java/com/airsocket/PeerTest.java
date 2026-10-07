package com.airsocket;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

public class PeerTest
{
    @Test
    public void testPeerRecordAccessorsAndEquality() throws Exception
    {
        InetAddress addr1 = InetAddress.getByName("127.0.0.1");
        InetAddress addr2 = InetAddress.getByName("127.0.0.1");
        InetAddress addr3 = InetAddress.getByName("192.168.1.50");

        Peer peer1 = new Peer("amar-laptop", addr1, 9000, 15L);
        Peer peer2 = new Peer("amar-laptop", addr2, 9000, 15L);
        Peer peer3 = new Peer("nas-server", addr3, 9001, 25L);

        assertEquals("amar-laptop", peer1.hostname());
        assertEquals(addr1, peer1.addr());
        assertEquals(9000, peer1.port());
        assertEquals(15L, peer1.rttMs());

        assertEquals(peer1, peer2);
        assertEquals(peer1.hashCode(), peer2.hashCode());
        assertNotEquals(peer1, peer3);
        assertTrue(peer1.toString().contains("amar-laptop"));
    }
}
