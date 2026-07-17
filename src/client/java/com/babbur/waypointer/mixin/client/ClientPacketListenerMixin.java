package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.dungeon.DungeonSoundHook;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /**
     * TAIL, not HEAD: the handler re-schedules itself onto the client main
     * thread and aborts the netty-thread invocation, so injecting at TAIL runs
     * the hook exactly once and always on the main thread.
     */
    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void waypointer$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        DungeonSoundHook.onSoundPacket(
                packet.getSound().value(),
                packet.getVolume(),
                packet.getX(),
                packet.getY(),
                packet.getZ());
    }
}
