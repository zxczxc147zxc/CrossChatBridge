package com.zxczxc147zxc.crosschat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public class ConfigLoader {
    private static final Properties props = new Properties();
    private static final String CONFIG_PATH = "config/crosschat.properties";

    private static final int PORT_MIN = 1024;
    private static final int PORT_MAX = 65535;

    public static void load() {
        Path path = Paths.get(CONFIG_PATH);
        if (!Files.exists(path)) {
            createDefault(path);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
        if (fixConfig()) {
            save(path);
        }
    }

    private static boolean fixConfig() {
        boolean changed = false;

        String mode = props.getProperty("server.mode");
        if (mode == null || (!"HOST".equalsIgnoreCase(mode) && !"CLIENT".equalsIgnoreCase(mode))) {
            System.err.println("[CrossChatBridge] Invalid server.mode '" + mode + "', reset to CLIENT");
            props.setProperty("server.mode", "CLIENT");
            changed = true;
        }

        changed |= fixPort("host.port");
        changed |= fixPort("host.listen_port");

        return changed;
    }

    private static boolean fixPort(String key) {
        String val = props.getProperty(key);
        if (val == null) return false;
        try {
            int port = Integer.parseInt(val);
            if (port >= PORT_MIN && port <= PORT_MAX) return false;
            System.err.println("[CrossChatBridge] " + key + "=" + port + " out of range (" + PORT_MIN + "-" + PORT_MAX + "), reset to 52134");
        } catch (NumberFormatException e) {
            System.err.println("[CrossChatBridge] " + key + "='" + val + "' is not a valid number, reset to 52134");
        }
        props.setProperty(key, "52134");
        return true;
    }

    private static void save(Path path) {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            props.store(writer, "CrossChat Config (auto-fixed)");
        } catch (IOException e) {
            System.err.println("[CrossChatBridge] Failed to save corrected config: " + e.getMessage());
        }
    }

    private static void createDefault(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                props.setProperty("server.name", "MyServer");
                props.setProperty("server.mode", "CLIENT");
                props.setProperty("host.ip", "127.0.0.1");
                props.setProperty("host.port", "52134");
                props.setProperty("host.listen_port", "52134");
                props.store(writer, "CrossChat Config");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create default config", e);
        }
    }

    public static String getServerName() {
        return props.getProperty("server.name", "Unknown");
    }

    public static boolean isHost() {
        return "HOST".equalsIgnoreCase(props.getProperty("server.mode"));
    }

    public static String getHostIP() {
        return props.getProperty("host.ip", "127.0.0.1");
    }

    public static int getHostPort() {
        return Integer.parseInt(props.getProperty("host.port", "52134"));
    }

    public static int getListenPort() {
        return Integer.parseInt(props.getProperty("host.listen_port", "52134"));
    }
}
