package dev.ethan.waypointer.mixin.client;

import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.chat.WaypointerContributorBadge;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void waypointer$badgeContributor(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(WaypointerContributorBadge.apply(cir.getReturnValue(), WaypointerClient.config()));
    }
}
