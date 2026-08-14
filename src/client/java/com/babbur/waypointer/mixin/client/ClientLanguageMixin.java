package com.babbur.waypointer.mixin.client;

import com.babbur.waypointer.i18n.RemoteLocaleResourceManager;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin {
    @ModifyVariable(method = "loadFrom", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static ResourceManager waypointer$addRemoteLanguage(ResourceManager resources) {
        return RemoteLocaleResourceManager.wrap(resources);
    }
}
