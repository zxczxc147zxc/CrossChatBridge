package com.zxczxc147zxc.crosschat.mixin;

import com.zxczxc147zxc.crosschat.ConfigLoader;
import com.zxczxc147zxc.crosschat.NetworkManager;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mixin(MinecraftServer.class)
public abstract class ServerStatusMixin {

    @Inject(method = "getStatus", at = @At("RETURN"), cancellable = true)
    private void onGetStatus(CallbackInfoReturnable<ServerStatus> cir) {
        if (!ConfigLoader.isHost() || !ConfigLoader.isPlayerListSyncEnabled()) return;

        ServerStatus original = cir.getReturnValue();
        if (original == null) return;

        ServerStatus.Players originalPlayers = original.players().orElse(null);
        if (originalPlayers == null) return;

        Map<String, List<String>> remoteLists = NetworkManager.getRemotePlayerLists();
        String selfName = ConfigLoader.getServerName();

        boolean hasRemotePlayers = remoteLists.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(selfName) && !e.getValue().isEmpty());
        if (!hasRemotePlayers) return;

        List<NameAndId> sample = new ArrayList<>();
        if (originalPlayers.sample() != null) {
            sample.addAll(originalPlayers.sample());
        }

        int maxSampleSize = 256;
        for (Map.Entry<String, List<String>> entry : remoteLists.entrySet()) {
            if (entry.getKey().equals(selfName)) continue;
            String serverName = entry.getKey();
            for (String name : entry.getValue()) {
                if (sample.size() >= maxSampleSize) break;
                sample.add(NameAndId.createOffline("[" + serverName + "] " + name));
            }
            if (sample.size() >= maxSampleSize) break;
        }

        int totalOnline = originalPlayers.online();
        for (Map.Entry<String, List<String>> entry : remoteLists.entrySet()) {
            if (entry.getKey().equals(selfName)) continue;
            totalOnline += entry.getValue().size();
        }

        ServerStatus.Players newPlayers = new ServerStatus.Players(
                originalPlayers.max(),
                totalOnline,
                sample
        );

        ServerStatus newStatus = new ServerStatus(
                original.description(),
                Optional.of(newPlayers),
                original.version(),
                original.favicon(),
                original.enforcesSecureChat()
        );

        cir.setReturnValue(newStatus);
    }
}
