package com.airsocket;
import java.net.InetAddress;

public record Peer(String hostname, InetAddress addr, int port, long rttMs)
{
}