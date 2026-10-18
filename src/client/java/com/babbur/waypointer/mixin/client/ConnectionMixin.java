package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.debug.ServerTimingDiagnostics;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void waypointer$receiveTiming(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ServerTimingDiagnostics.received((Connection) (Object) this, packet, System.nanoTime());
    }
}
