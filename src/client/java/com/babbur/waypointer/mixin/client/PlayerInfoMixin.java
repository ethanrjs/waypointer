package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.render.HappySnowmanSession;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void waypointer$happySnowmanSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        cir.setReturnValue(HappySnowmanSession.override(cir.getReturnValue()));
    }
}
