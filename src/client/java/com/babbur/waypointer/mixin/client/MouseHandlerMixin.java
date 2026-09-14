package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.input.WaypointRepositionMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** Edit mode turns the wheel into a placement-distance control before the hotbar sees it. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void waypointer$editModeScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (WaypointRepositionMode.handleScroll(Minecraft.getInstance(), yOffset)) ci.cancel();
    }
}
