package com.zxczxc147zxc.crosschat;

import com.google.gson.Gson;

public class ChatPacket {
    public static final String TYPE_REGISTER = "REGISTER";
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_BROADCAST = "BROADCAST";

    private static final Gson GSON = new Gson();

    private String type;
    private String server;
    private String player;
    private String msg;
    private String formatted;
    private String key;

    public ChatPacket() {}

    public ChatPacket(String type, String server, String player, String msg) {
        this.type = type;
        this.server = server;
        this.player = player;
        this.msg = msg;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getServer() { return server; }
    public void setServer(String server) { this.server = server; }
    public String getPlayer() { return player; }
    public void setPlayer(String player) { this.player = player; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public String getFormatted() { return formatted; }
    public void setFormatted(String formatted) { this.formatted = formatted; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getFormattedMessage() {
        if (formatted != null) return formatted;
        return "[" + server + "]<" + player + ">" + msg;
    }

    public String toJson() {
        return GSON.toJson(this) + "\n";
    }

    public static ChatPacket fromJson(String json) {
        return GSON.fromJson(json, ChatPacket.class);
    }
}
