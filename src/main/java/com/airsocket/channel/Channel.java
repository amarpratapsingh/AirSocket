package com.airsocket.channel;

import com.airsocket.Peer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Channel implements AutoCloseable
{
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;

    private Channel(Socket socket) throws IOException
    {
        this.socket = socket;
        this.out = new DataOutputStream(socket.getOutputStream());
        this.in = new DataInputStream(socket.getInputStream());
    }

    public static Channel open(Peer peer) throws IOException
    {
        Socket socket = new Socket(peer.addr(), peer.port());
        return new Channel(socket);
    }

    public void send(byte[] data) throws IOException
    {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    public byte[] receive() throws IOException
    {
        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static final List<ServerSocket> activeServers = new ArrayList<>();

    public static void listen(int port, Consumer<byte[]> handler) throws IOException
    {
        ServerSocket serverSocket = new ServerSocket(port);
        synchronized (activeServers)
        {
            activeServers.add(serverSocket);
        }

        Thread listenerThread = new Thread(() ->
        {
            try
            {
                while (!serverSocket.isClosed())
                {
                    Socket clientSocket = serverSocket.accept();
                    Thread clientThread = new Thread(() ->
                    {
                        try (DataInputStream inClient = new DataInputStream(clientSocket.getInputStream()))
                        {
                            while (!clientSocket.isClosed())
                            {
                                int length = inClient.readInt();
                                byte[] data = new byte[length];
                                inClient.readFully(data);
                                handler.accept(data);
                            }
                        }
                        catch (EOFException e)
                        {
                            // Clean disconnection
                        }
                        catch (IOException e)
                        {
                            // Connection error or closure
                        }
                        finally
                        {
                            try
                            {
                                clientSocket.close();
                            }
                            catch (IOException e)
                            {
                                // Ignore
                            }
                        }
                    });
                    clientThread.setDaemon(true);
                    clientThread.start();
                }
            }
            catch (SocketException e)
            {
                // Socket closed cleanly
            }
            catch (IOException e)
            {
                // Other IO errors
            }
            finally
            {
                synchronized (activeServers)
                {
                    activeServers.remove(serverSocket);
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public static void closeAllListeners()
    {
        List<ServerSocket> toClose;
        synchronized (activeServers)
        {
            toClose = new ArrayList<>(activeServers);
            activeServers.clear();
        }
        for (ServerSocket ss : toClose)
        {
            try
            {
                ss.close();
            }
            catch (IOException e)
            {
                // Ignore
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        socket.close();
    }
}
