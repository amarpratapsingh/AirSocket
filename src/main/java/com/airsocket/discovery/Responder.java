package com.airsocket.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

public class Responder implements AutoCloseable
{
    private static final int DISCOVERY_PORT = 42069;
    private final DatagramSocket socket;
    private final Thread thread;
    private final int tcpPort;
    private volatile boolean running = true;

    public Responder(int tcpPort) throws IOException
    {
        this.tcpPort = tcpPort;
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.bind(new InetSocketAddress(DISCOVERY_PORT));

        this.thread = new Thread(this::listenLoop);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void listenLoop()
    {
        byte[] buffer = new byte[1024];
        while (running)
        {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try
            {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                if (message.contains("\"type\":\"ping\""))
                {
                    String hostname = "unknown";
                    try
                    {
                        hostname = InetAddress.getLocalHost().getHostName();
                    }
                    catch (Exception e)
                    {
                        // Ignore
                    }

                    String pongMsg = String.format("{\"type\":\"pong\",\"hostname\":\"%s\",\"port\":%d}", hostname, tcpPort);
                    byte[] sendData = pongMsg.getBytes("UTF-8");

                    DatagramPacket responsePacket = new DatagramPacket(
                        sendData, sendData.length,
                        packet.getSocketAddress()
                    );
                    socket.send(responsePacket);
                }
            }
            catch (SocketException e)
            {
                break;
            }
            catch (IOException e)
            {
                // Error receiving/sending packet
            }
        }
    }

    @Override
    public void close()
    {
        running = false;
        if (socket != null && !socket.isClosed())
        {
            socket.close();
        }
        thread.interrupt();
    }
}
