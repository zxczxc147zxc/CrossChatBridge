package com.zxczxc147zxc.crosschat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class CrossChatMod implements ModInitializer {
    private static MinecraftServer server;

    @Override
    public void onInitialize() {
        ConfigLoader.load();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            NetworkManager.sendPlayerUpdate();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> NetworkManager.shutdown());

        if (ConfigLoader.isHost()) {
            NetworkManager.startServer();
        } else {
            NetworkManager.startClient();
        }

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String content = message.signedContent();
            String playerName = sender.getName().getString();
            String serverName = ConfigLoader.getServerName();

            ChatPacket packet = new ChatPacket(ChatPacket.TYPE_CHAT, serverName, playerName, content);

            if (ConfigLoader.isHost()) {
                NetworkManager.broadcastToClients(packet);
                broadcastLocally(packet);
            } else {
                NetworkManager.sendToHost(packet);
            }
            return false;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("crosschat")
                .then(Commands.literal("reload")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(context -> {
                        ConfigLoader.load();
                        NetworkManager.reload();
                        context.getSource().sendSuccess(() -> Component.literal("[CrossChatBridge] Config reloaded."), false);
                        return 1;
                    })
                )
            )
        );

        ServerPlayerEvents.JOIN.register(player -> {
            NetworkManager.addLocalPlayer(player.getName().getString());
            NetworkManager.sendPlayerUpdate();
            if (ConfigLoader.isHost() && ConfigLoader.isTabListSyncEnabled()) {
                NetworkManager.sendVirtualPlayersTo(player);
            }
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            NetworkManager.removeLocalPlayer(player.getName().getString());
            NetworkManager.sendPlayerUpdate();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(NetworkManager::shutdown));
    }

    private void broadcastLocally(ChatPacket packet) {
        if (server != null) {
            String formatted = packet.getFormattedMessage();
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false));
        }
    }
}
