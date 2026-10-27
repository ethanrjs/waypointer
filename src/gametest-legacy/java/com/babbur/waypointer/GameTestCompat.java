package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;

final class GameTestCompat {
    private GameTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getClientLevel().waitForChunksRender();
    }

    static void selectAll(ClientGameTestContext context) {
        context.runOnClient(client -> MinecraftCompat.screen(client).keyPressed(
                new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL)));
    }
}
