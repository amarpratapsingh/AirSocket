package com.airsocket;

import com.airsocket.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class ChannelTest
{
    @AfterEach
    public void tearDown()
    {
        Channel.closeAllListeners();
    }

    @Test
    public void testListenAndConnect() throws Exception
    {
        int testPort = 19090;
        BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

        Channel.listen(testPort, (data) ->
        {
            try
            {
                receivedMessages.put(new String(data, "UTF-8"));
            }
            catch (Exception e)
            {
                fail(e);
            }
        });

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline)
        {
            try (java.net.Socket probe = new java.net.Socket(InetAddress.getByName("127.0.0.1"), testPort))
            {
                break;
            }
            catch (Exception ignored)
            {
                Thread.sleep(20);
            }
        }

        Peer peer = new Peer("localhost", InetAddress.getByName("127.0.0.1"), testPort, 0);
        try (Channel channel = Channel.open(peer))
        {
            channel.send("Hello World!".getBytes("UTF-8"));
            channel.send("Second Message".getBytes("UTF-8"));

            String msg1 = receivedMessages.poll(2, TimeUnit.SECONDS);
            assertEquals("Hello World!", msg1);

            String msg2 = receivedMessages.poll(2, TimeUnit.SECONDS);
            assertEquals("Second Message", msg2);
        }
    }
}
