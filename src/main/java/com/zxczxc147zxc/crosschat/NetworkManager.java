package com.zxczxc147zxc.crosschat;

import com.mojang.authlib.GameProfile;
import com.zxczxc147zxc.crosschat.mixin.ClientboundPlayerInfoUpdatePacketAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import java.util.*;
import java.util.stream.Collectors;

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
    private static final Set<String> localPlayerNames = Collections.synchronizedSet(new HashSet<>());

    private static final Map<String, List<String>> remotePlayerLists = new ConcurrentHashMap<>();
    private static final Map<UUID, VirtualPlayerInfo> virtualPlayers = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> serverPlayerNames = new ConcurrentHashMap<>();

    private static final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> ADD_ACTIONS = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
    );

    public static class VirtualPlayerInfo {
        public final UUID id;
        public final String rawName;
        public final String serverName;

        public VirtualPlayerInfo(UUID id, String rawName, String serverName) {
            this.id = id;
            this.rawName = rawName;
            this.serverName = serverName;
        }

        public Component displayName() {
            return Component.literal("[" + serverName + "] " + rawName);
        }
    }

    private static UUID virtualPlayerUUID(String serverName, String playerName) {
        return UUID.nameUUIDFromBytes(("remote:" + serverName + ":" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Concat(String a, String b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(a.getBytes(StandardCharsets.UTF_8));
            md.update(b.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte bb : hash) {
                hex.append(String.format("%02x", bb));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static volatile boolean running = true;
    private static volatile MinecraftServer serverInstance;

    static {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> serverInstance = null);
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
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String firstLine = reader.readLine();
            if (firstLine == null) { socket.close(); return; }
            ChatPacket hello = ChatPacket.fromJson(firstLine);
            if (!ChatPacket.TYPE_HELLO.equals(hello.getType())) {
                System.err.println("[CrossChatBridge] Rejected: expected HELLO, got " + hello.getType());
                socket.close(); return;
            }
            String serverName = hello.getServer();
            if (serverName == null || serverName.isEmpty()) { socket.close(); return; }

            String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            ChatPacket challenge = new ChatPacket();
            challenge.setType(ChatPacket.TYPE_CHALLENGE);
            challenge.setKey(nonce);
            writer.write(challenge.toJson());
            writer.flush();

            String authLine = reader.readLine();
            if (authLine == null) { socket.close(); return; }
            ChatPacket auth = ChatPacket.fromJson(authLine);
            if (!ChatPacket.TYPE_AUTH.equals(auth.getType())) {
                System.err.println("[CrossChatBridge] Rejected: expected AUTH, got " + auth.getType());
                socket.close(); return;
            }

            String expected = sha256Concat(ConfigLoader.getSecretHash(), nonce);
            if (!expected.equals(auth.getKey())) {
                System.err.println("[CrossChatBridge] Auth failed for " + serverName + " from " + socket.getRemoteSocketAddress());
                socket.close(); return;
            }

            socket.setSoTimeout(0);
            clientWriters.put(serverName, writer);
            writerToServer.put(writer, serverName);
            System.out.println("[CrossChatBridge] Registered: " + serverName);

            String line;
            while ((line = reader.readLine()) != null) {
                ChatPacket p = ChatPacket.fromJson(line);

                if (ChatPacket.TYPE_CHAT.equals(p.getType())) {
                    ChatPacket trusted = new ChatPacket(ChatPacket.TYPE_BROADCAST, p.getServer(), p.getPlayer(), p.getMsg());
                    trusted.setFormatted("[" + p.getServer() + "]<" + p.getPlayer() + ">" + p.getMsg());
                    broadcastToClients(trusted);
                    MinecraftServer server = serverInstance;
                    if (server != null) {
                        String formatted = trusted.getFormattedMessage();
                        server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false));
                    }
                } else if (ChatPacket.TYPE_PLAYER_UPDATE.equals(p.getType())) {
                    List<String> newList = p.getPlayerList() != null ? p.getPlayerList() : Collections.emptyList();
                    remotePlayerLists.put(serverName, newList);
                    invalidateServerStatus();
                    if (ConfigLoader.isHost() && ConfigLoader.isTabListSyncEnabled()) {
                        syncVirtualPlayers(serverName, newList);
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            System.err.println("[CrossChatBridge] Handshake timeout from " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            // connection closed
        } finally {
            if (writer != null) {
                String name = writerToServer.remove(writer);
                if (name != null) {
                    clientWriters.remove(name);
                    remotePlayerLists.remove(name);
                    invalidateServerStatus();
                    if (ConfigLoader.isHost() && ConfigLoader.isTabListSyncEnabled()) {
                        removeVirtualPlayersForServer(name);
                    }
                    System.out.println("[CrossChatBridge] Disconnected: " + name);
                }
            }
            try { socket.close(); } catch (IOException ignored) {}
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
            clientSocket.setSoTimeout(10000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

            ChatPacket hello = new ChatPacket(ChatPacket.TYPE_HELLO, ConfigLoader.getServerName(), null, null);
            writer.write(hello.toJson());
            writer.flush();

            String challengeLine = reader.readLine();
            if (challengeLine == null) throw new IOException("No challenge received");
            ChatPacket challenge = ChatPacket.fromJson(challengeLine);
            if (!ChatPacket.TYPE_CHALLENGE.equals(challenge.getType())) {
                throw new IOException("Expected CHALLENGE, got " + challenge.getType());
            }
            String nonce = challenge.getKey();

            String response = sha256Concat(ConfigLoader.getSecretHash(), nonce);
            ChatPacket auth = new ChatPacket(ChatPacket.TYPE_AUTH, ConfigLoader.getServerName(), null, null);
            auth.setKey(response);
            writer.write(auth.toJson());
            writer.flush();

            clientSocket.setSoTimeout(0);
            clientWriter = writer;
            System.out.println("[CrossChatBridge] Authenticated with host " + host + ":" + port);

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

            sendPlayerUpdate();

        } catch (IOException e) {
            if (running) {
                System.err.println("[CrossChatBridge] Failed to connect/authenticate to host, retrying in 5s...");
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

    public static void addLocalPlayer(String name) {
        localPlayerNames.add(name);
    }

    public static void removeLocalPlayer(String name) {
        localPlayerNames.remove(name);
    }

    public static void sendPlayerUpdate() {
        if (!ConfigLoader.isPlayerListSyncEnabled()) return;
        MinecraftServer server = serverInstance;
        if (server == null) return;

        List<String> names;
        if (ConfigLoader.isHost()) {
            names = server.getPlayerList().getPlayers().stream()
                .map(p -> p.getName().getString())
                .collect(Collectors.toList());
        } else {
            synchronized (localPlayerNames) {
                names = new ArrayList<>(localPlayerNames);
            }
        }

        ChatPacket packet = new ChatPacket();
        packet.setType(ChatPacket.TYPE_PLAYER_UPDATE);
        packet.setServer(ConfigLoader.getServerName());
        packet.setPlayerList(names);

        if (ConfigLoader.isHost()) {
            remotePlayerLists.put(ConfigLoader.getServerName(), names);
            invalidateServerStatus();
        } else {
            sendToHost(packet);
        }
    }

    public static Map<String, List<String>> getRemotePlayerLists() {
        return Collections.unmodifiableMap(remotePlayerLists);
    }

    private static void syncVirtualPlayers(String serverName, List<String> newNames) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        server.execute(() -> {
            List<String> old = serverPlayerNames.remove(serverName);
            if (old != null) {
                for (String name : old) {
                    UUID id = virtualPlayerUUID(serverName, name);
                    virtualPlayers.remove(id);
                    broadcastPlayerRemove(id);
                }
            }
            if (newNames != null && !newNames.isEmpty()) {
                for (String name : newNames) {
                    UUID id = virtualPlayerUUID(serverName, name);
                    VirtualPlayerInfo info = new VirtualPlayerInfo(id, name, serverName);
                    virtualPlayers.put(id, info);
                    broadcastPlayerAdd(info);
                }
                serverPlayerNames.put(serverName, new ArrayList<>(newNames));
            }
        });
    }

    private static void removeVirtualPlayersForServer(String serverName) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        server.execute(() -> {
            List<String> names = serverPlayerNames.remove(serverName);
            if (names != null) {
                for (String name : names) {
                    UUID id = virtualPlayerUUID(serverName, name);
                    virtualPlayers.remove(id);
                    broadcastPlayerRemove(id);
                }
            }
        });
    }

    private static void broadcastPlayerAdd(VirtualPlayerInfo info) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        GameProfile profile = new GameProfile(info.id, info.rawName);
        Component displayName = info.displayName();
        if (displayName == null) displayName = Component.literal(info.rawName);
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                info.id, profile, true, 0, GameType.SURVIVAL, displayName, false, 0, null);
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                ADD_ACTIONS, Collections.emptyList());
        ((ClientboundPlayerInfoUpdatePacketAccessor) packet).setEntries(Collections.singletonList(entry));
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer p : players) {
            p.connection.send(packet);
        }
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.connection.send(packet);
            }
        });
    }

    private static void broadcastPlayerRemove(UUID id) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(id));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(packet);
        }
    }

    public static void sendVirtualPlayersTo(ServerPlayer target) {
        if (virtualPlayers.isEmpty()) return;
        for (VirtualPlayerInfo info : virtualPlayers.values()) {
            GameProfile profile = new GameProfile(info.id, info.rawName);
            Component displayName = info.displayName();
            if (displayName == null) displayName = Component.literal(info.rawName);
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                    info.id, profile, true, 0, GameType.SURVIVAL, displayName, false, 0, null);
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                    ADD_ACTIONS, Collections.emptyList());
            ((ClientboundPlayerInfoUpdatePacketAccessor) packet).setEntries(Collections.singletonList(entry));
            target.connection.send(packet);
        }
    }

    private static void invalidateServerStatus() {
        MinecraftServer server = serverInstance;
        if (server != null) {
            server.invalidateStatus();
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
        localPlayerNames.clear();
        virtualPlayers.clear();
        serverPlayerNames.clear();
        clientWriters.clear();
        writerToServer.clear();
        clientWriter = null;
        threadPool.shutdownNow();
    }

    public static void reload() {
        shutdown();
        localPlayerNames.clear();
        virtualPlayers.clear();
        serverPlayerNames.clear();
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
