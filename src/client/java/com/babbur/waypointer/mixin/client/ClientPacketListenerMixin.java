package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.dungeon.DungeonSoundHook;
import com.babbur.waypointer.crystal.CrystalHollowsParticleHook;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void waypointer$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        DungeonSoundHook.onSoundPacket(
                packet.getSound().value(),
                packet.getVolume(),
                packet.getX(),
                packet.getY(),
                packet.getZ());
    }

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void waypointer$onParticleEvent(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        CrystalHollowsParticleHook.onParticlePacket(
                packet.getParticle().getType(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getCount());
    }
}
