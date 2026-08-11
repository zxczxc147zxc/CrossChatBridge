package com.zxczxc147zxc.crosschat.mixin;

import com.zxczxc147zxc.crosschat.ConfigLoader;
import com.zxczxc147zxc.crosschat.NetworkManager;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void crosschat$onPlayerJoined(Connection connection, ServerPlayer player,
                                          CommonListenerCookie cookie, CallbackInfo ci) {
        String name = player.getName().getString();
        NetworkManager.addLocalPlayer(name);
        NetworkManager.sendPlayerUpdate();
        NetworkManager.announceJoinLeave(name, true);
        if (ConfigLoader.isTabListSyncEnabled()) {
            NetworkManager.sendVirtualPlayersTo(player);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void crosschat$onPlayerLeft(ServerPlayer player, CallbackInfo ci) {
        String name = player.getName().getString();
        NetworkManager.removeLocalPlayer(name);
        NetworkManager.sendPlayerUpdate();
        NetworkManager.announceJoinLeave(name, false);
    }
}