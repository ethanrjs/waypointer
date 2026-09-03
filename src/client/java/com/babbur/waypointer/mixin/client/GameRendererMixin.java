package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.render.gpu.OverlayRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the optional post-world overlay after level rendering. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void waypointer$afterLevelRendered(CallbackInfo ci) {
        OverlayRenderer renderer = OverlayRenderer.activeOrNull();
        if (renderer != null) renderer.onAfterLevelRendered();
    }
}
