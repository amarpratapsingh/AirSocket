package com.airsocket;

import com.airsocket.channel.Channel;
import com.airsocket.discovery.Discoverer;
import com.airsocket.discovery.Responder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public class AirSocket
{
    public static List<Peer> discover(Duration timeout) throws IOException
    {
        return Discoverer.scan(timeout);
    }
    public static Responder respond(int port) throws IOException
    {
        return new Responder(port);
    }

    public static Channel connect(Peer peer) throws IOException
    {
        return Channel.open(peer);
    }

    public static void listen(int port, Consumer<byte[]> handler) throws IOException
    {
        Channel.listen(port, handler);
    }
}
