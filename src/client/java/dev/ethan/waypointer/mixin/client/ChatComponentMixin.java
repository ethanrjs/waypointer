package dev.ethan.waypointer.mixin.client;

import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.chat.WaypointerContributorBadge;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @ModifyVariable(method = "addServerSystemMessage", at = @At("HEAD"), argsOnly = true)
    private Component waypointer$badgeServerSystemMessage(Component message) {
        return WaypointerContributorBadge.apply(message, WaypointerClient.config());
    }

    @ModifyVariable(method = "addPlayerMessage", at = @At("HEAD"), argsOnly = true)
    private Component waypointer$badgePlayerMessage(Component message) {
        return WaypointerContributorBadge.apply(message, WaypointerClient.config());
    }
}
