package com.airsocket.discovery;

import com.airsocket.Peer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Discoverer
{
    private static final int DISCOVERY_PORT = 42069;

    public static List<Peer> scan(Duration timeout) throws IOException
    {
        List<Peer> peers = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        try (DatagramSocket socket = new DatagramSocket())
        {
            socket.setBroadcast(true);
            socket.setSoTimeout(100);

            String hostname = "unknown";
            try
            {
                hostname = InetAddress.getLocalHost().getHostName();
            }
            catch (Exception e)
            {
                // Ignore
            }

            String pingMsg = String.format("{\"type\":\"ping\",\"hostname\":\"%s\",\"port\":0}", hostname);
            byte[] sendData = pingMsg.getBytes("UTF-8");

            List<InetAddress> targetAddresses = new ArrayList<>();
            targetAddresses.add(InetAddress.getByName("255.255.255.255"));
            targetAddresses.add(InetAddress.getByName("127.0.0.1"));

            try
            {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces != null)
                {
                    while (interfaces.hasMoreElements())
                    {
                        NetworkInterface netInterface = interfaces.nextElement();
                        if (netInterface.isUp() && !netInterface.isLoopback())
                        {
                            for (InterfaceAddress interfaceAddress : netInterface.getInterfaceAddresses())
                            {
                                InetAddress broadcast = interfaceAddress.getBroadcast();
                                if (broadcast != null && !targetAddresses.contains(broadcast))
                                {
                                    targetAddresses.add(broadcast);
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                // Fallback to basic targets
            }

            for (InetAddress target : targetAddresses)
            {
                try
                {
                    DatagramPacket sendPacket = new DatagramPacket(
                        sendData, sendData.length,
                        target, DISCOVERY_PORT
                    );
                    socket.send(sendPacket);
                }
                catch (IOException e)
                {
                    // Ignore send errors for individual targets
                }
            }

            long sendTime = System.currentTimeMillis();
            byte[] receiveBuffer = new byte[1024];

            while (System.currentTimeMillis() - startTime < timeout.toMillis())
            {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                try
                {
                    socket.receive(receivePacket);
                    long receiveTime = System.currentTimeMillis();
                    long rtt = receiveTime - sendTime;

                    String message = new String(receivePacket.getData(), 0, receivePacket.getLength(), "UTF-8");
                    if (message.contains("\"type\":\"pong\""))
                    {
                        Pattern namePat = Pattern.compile("\"hostname\":\"([^\"]+)\"");
                        Matcher nameMat = namePat.matcher(message);
                        String peerName = nameMat.find() ? nameMat.group(1) : "unknown";

                        Pattern portPat = Pattern.compile("\"port\":(\\d+)");
                        Matcher portMat = portPat.matcher(message);
                        int peerPort = portMat.find() ? Integer.parseInt(portMat.group(1)) : 0;

                        InetAddress addr = receivePacket.getAddress();

                        boolean exists = false;
                        for (Peer p : peers)
                        {
                            if (p.addr().equals(addr) && p.port() == peerPort)
                            {
                                exists = true;
                                break;
                            }
                        }

                        if (!exists)
                        {
                            peers.add(new Peer(peerName, addr, peerPort, rtt));
                        }
                    }
                }
                catch (SocketTimeoutException e)
                {
                    // Timeout and check loop condition
                }
            }
        }

        return peers;
    }
}
