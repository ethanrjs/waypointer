package dev.ethan.waypointer.mixin.client;

import dev.ethan.waypointer.diana.WaypointerParticleEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleParticleEvent", at = @At("HEAD"))
    private void waypointer$onParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        WaypointerParticleEvents.emit(packet);
    }
}
