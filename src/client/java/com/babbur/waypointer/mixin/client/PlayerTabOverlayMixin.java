package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.WaypointerContributorBadge;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void waypointer$badgeContributor(PlayerInfo playerInfo,
                                             CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(WaypointerContributorBadge.apply(cir.getReturnValue(), WaypointerClient.config()));
    }
}
