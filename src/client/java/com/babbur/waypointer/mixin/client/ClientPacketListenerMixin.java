package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.compat.ParticlePacketCompat;
import com.babbur.waypointer.dungeon.DungeonItemPickupHook;
import com.babbur.waypointer.dungeon.DungeonSoundHook;
import com.babbur.waypointer.crystal.CrystalHollowsParticleHook;
import com.babbur.waypointer.crystal.MetalDetectorController;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @ModifyExpressionValue(method = "setActionBarText", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetActionBarTextPacket;text()Lnet/minecraft/network/chat/Component;"))
    private Component waypointer$onActionBar(Component message) {
        return MetalDetectorController.onActionBarPacket(message);
    }

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void waypointer$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        DungeonSoundHook.onSoundPacket(
                packet.getSound().value(),
                packet.getVolume(),
                packet.getX(),
                packet.getY(),
                packet.getZ());
    }

    @Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void waypointer$onTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(packet.getItemId()) instanceof ItemEntity item)) return;
        DungeonItemPickupHook.onItemTaken(packet.getPlayerId(),
                item.getItem().getHoverName().getString(), item.getX(), item.getY(), item.getZ());
    }

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void waypointer$onParticleEvent(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        ParticlePacketCompat.onPacket(packet);
    }
}
