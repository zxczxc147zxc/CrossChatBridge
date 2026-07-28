package com.zxczxc147zxc.crosschat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.*;

public class NetworkManager {
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static Socket clientSocket;
    private static PrintWriter clientWriter;

    private static ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, PrintWriter> clientWriters = new ConcurrentHashMap<>();
    private static final Map<PrintWriter, String> writerToServer = new ConcurrentHashMap<>();
    private static volatile boolean running = true;
    private static volatile MinecraftServer serverInstance;

    static {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverInstance = null;
        });
    }

    public static void startServer() {
        int port = ConfigLoader.getListenPort();
        try {
            if (acceptThread != null) {
                acceptThread.interrupt();
                acceptThread = null;
            }
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
            ServerSocket ss = new ServerSocket(port);
            serverSocket = ss;
            System.out.println("[CrossChatBridge] Host listening on port " + port);
            acceptThread = new Thread(() -> {
                while (running && !ss.isClosed()) {
                    try {
                        Socket socket = ss.accept();
                        threadPool.submit(() -> handleClient(socket));
                    } catch (IOException e) {
                        if (running && !ss.isClosed()) e.printStackTrace();
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        PrintWriter writer = null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String firstLine = reader.readLine();
            if (firstLine == null) return;
            ChatPacket register = ChatPacket.fromJson(firstLine);
            if (!ChatPacket.TYPE_REGISTER.equals(register.getType())) return;

            String serverName = register.getServer();
            if (!ConfigLoader.getSecretHash().equals(register.getKey())) {
                System.err.println("[CrossChatBridge] Rejected connection from " + socket.getRemoteSocketAddress() + ": invalid key");
                try { socket.close(); } catch (IOException ignored) {}
                return;
            }
            clientWriters.put(serverName, writer);
            writerToServer.put(writer, serverName);
            System.out.println("[CrossChatBridge] Registered: " + serverName);

            String line;
            while ((line = reader.readLine()) != null) {
                ChatPacket p = ChatPacket.fromJson(line);
                if (ChatPacket.TYPE_CHAT.equals(p.getType())) {
                    broadcastToClients(p);
                    MinecraftServer server = serverInstance;
                    if (server != null) {
                        String formatted = p.getFormattedMessage();
                        server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false));
                    }
                }
            }
        } catch (IOException e) {
            // connection closed
        } finally {
            if (writer != null) {
                String name = writerToServer.remove(writer);
                if (name != null) {
                    clientWriters.remove(name);
                    System.out.println("[CrossChatBridge] Disconnected: " + name);
                }
            }
        }
    }

    public static void broadcastToClients(ChatPacket original) {
        ChatPacket broadcast = new ChatPacket();
        broadcast.setType(ChatPacket.TYPE_BROADCAST);
        broadcast.setFormatted(original.getFormattedMessage());
        String json = broadcast.toJson();
        for (PrintWriter w : clientWriters.values()) {
            w.write(json);
            w.flush();
        }
    }

    public static void startClient() {
        String host = ConfigLoader.getHostIP();
        int port = ConfigLoader.getHostPort();
        try {
            clientSocket = new Socket(host, port);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

            ChatPacket reg = new ChatPacket(ChatPacket.TYPE_REGISTER, ConfigLoader.getServerName(), null, null);
            reg.setKey(ConfigLoader.getSecretHash());
            writer.write(reg.toJson());
            writer.flush();
            clientWriter = writer;
            System.out.println("[CrossChatBridge] Connected to host " + host + ":" + port);

            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ChatPacket p = ChatPacket.fromJson(line);
                        if (ChatPacket.TYPE_BROADCAST.equals(p.getType())) {
                            String formatted = p.getFormatted();
                            MinecraftServer server = serverInstance;
                            if (server != null) {
                                server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false));
                            }
                        }
                    }
                } catch (IOException ignored) {}
            });
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (IOException e) {
            if (running) {
                System.err.println("[CrossChatBridge] Failed to connect to host, retrying in 5s...");
                threadPool.schedule(NetworkManager::startClient, 5, TimeUnit.SECONDS);
            }
        }
    }

    public static void sendToHost(ChatPacket packet) {
        if (clientWriter != null) {
            clientWriter.write(packet.toJson());
            clientWriter.flush();
        }
    }

    public static void shutdown() {
        running = false;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        clientWriters.clear();
        writerToServer.clear();
        clientWriter = null;
        threadPool.shutdownNow();
    }

    public static void reload() {
        shutdown();
        clientWriters.clear();
        writerToServer.clear();
        threadPool = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        running = true;
        if (ConfigLoader.isHost()) {
            startServer();
        } else {
            startClient();
        }
    }
}
