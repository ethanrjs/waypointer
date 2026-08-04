package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.WaypointerContributorBadge;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
                    + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"))
    private void waypointer$badgeContributorNameTag(Avatar avatar, AvatarRenderState state,
                                                    float partialTick, CallbackInfo ci) {
        if (state.nameTag == null) return;
        String profileName = avatar.getProfile().name().orElse("");
        state.nameTag = WaypointerContributorBadge.applyPlayerName(
                state.nameTag, profileName, avatar.getProfile().partialProfile().id(),
                WaypointerClient.config());
    }
}
