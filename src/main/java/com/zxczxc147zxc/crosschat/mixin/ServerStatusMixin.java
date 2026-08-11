package com.zxczxc147zxc.crosschat.mixin;

import com.mojang.authlib.GameProfile;
import com.zxczxc147zxc.crosschat.ConfigLoader;
import com.zxczxc147zxc.crosschat.NetworkManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

        try {
            Object newPlayers = rebuildPlayers(originalPlayers, remoteLists, selfName);
            if (newPlayers == null) return;
            ServerStatus newStatus = rebuildStatus(original, newPlayers);
            if (newStatus == null) return;
            cir.setReturnValue(newStatus);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Object rebuildPlayers(ServerStatus.Players players, Map<String, List<String>> remoteLists, String selfName) throws Exception {
        Method sampleMethod = players.getClass().getMethod("sample");
        Object sampleRaw = sampleMethod.invoke(players);
        List<Object> sample = new ArrayList<>();
        if (sampleRaw != null) {
            sample.addAll((List<Object>) sampleRaw);
        }

        boolean nameAndId = !sample.isEmpty() && !(sample.get(0) instanceof GameProfile);

        int maxSampleSize = 256;
        for (Map.Entry<String, List<String>> entry : remoteLists.entrySet()) {
            if (entry.getKey().equals(selfName)) continue;
            String serverName = entry.getKey();
            for (String name : entry.getValue()) {
                if (sample.size() >= maxSampleSize) break;
                String display = "[" + serverName + "] " + name;
                Object item = nameAndId
                        ? makeNameAndId(UUID.nameUUIDFromBytes(display.getBytes(StandardCharsets.UTF_8)), display)
                        : new GameProfile(UUID.nameUUIDFromBytes(display.getBytes(StandardCharsets.UTF_8)), display);
                if (item == null) return null;
                sample.add(item);
            }
            if (sample.size() >= maxSampleSize) break;
        }

        int totalOnline = players.online();
        for (Map.Entry<String, List<String>> entry : remoteLists.entrySet()) {
            if (entry.getKey().equals(selfName)) continue;
            totalOnline += entry.getValue().size();
        }

        try {
            Constructor<ServerStatus.Players> ctor = ServerStatus.Players.class.getConstructor(int.class, int.class, List.class);
            return ctor.newInstance(players.max(), totalOnline, sample);
        } catch (NoSuchMethodException e) {
            Constructor<?> ctor = findPlayersCtor(ServerStatus.Players.class);
            if (ctor == null) return null;
            ctor.setAccessible(true);
            return ctor.newInstance(players.max(), totalOnline, sample);
        }
    }

    private static Constructor<?> findPlayersCtor(Class<?> playersClass) {
        for (Constructor<?> c : playersClass.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                return c;
            }
        }
        return null;
    }

    private static Object makeNameAndId(UUID id, String name) {
        try {
            String officialName = "net.minecraft.network.protocol.status.ServerStatus$NameAndId";
            String mapped;
            try {
                mapped = FabricLoader.getInstance().getMappingResolver().mapClassName("official", officialName);
            } catch (Throwable t) {
                mapped = null;
            }
            if (mapped == null || mapped.isEmpty()) mapped = officialName;
            Class<?> cls = Class.forName(mapped);
            return cls.getConstructor(UUID.class, String.class).newInstance(id, name);
        } catch (Throwable t) {
            // 旧 GameProfile 分支
            return null;
        }
    }

    private static ServerStatus rebuildStatus(ServerStatus original, Object newPlayers) throws Exception {
        Component description = original.description();
        Object version = original.version();
        Object favicon = original.favicon();
        boolean secure = original.enforcesSecureChat();

        Optional<String> nonce = null;
        try {
            Method nonceMethod = ServerStatus.class.getMethod("nonce");
            Object v = nonceMethod.invoke(original);
            if (v instanceof Optional) {
                nonce = (Optional<String>) v;
            }
        } catch (NoSuchMethodException ignored) {
        }

        for (Constructor<?> c : ServerStatus.class.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length < 5 || pt.length > 6) continue;
            if (!Component.class.isAssignableFrom(pt[0])) continue;
            Object[] args = new Object[pt.length];
            args[0] = description;
            args[1] = Optional.of(newPlayers);
            args[2] = version;
            args[3] = favicon;
            args[4] = secure;
            if (pt.length == 6) {
                args[5] = nonce != null ? nonce : Optional.empty();
            }
            c.setAccessible(true);
            return (ServerStatus) c.newInstance(args);
        }
        return null;
    }
}