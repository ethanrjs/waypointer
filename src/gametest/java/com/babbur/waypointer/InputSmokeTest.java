package com.babbur.waypointer;

import com.babbur.waypointer.compat.InputCompat;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.screen.WaypointerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;

/** Exercise normal keyboard/mouse callbacks, including 26.3 SDL scan codes. */
final class InputSmokeTest {
    private InputSmokeTest() {}

    static void run(ClientGameTestContext context) {
        context.getInput().pressKey(InputCompat.KEY_U);
        context.waitFor(client -> MinecraftCompat.screen(client) instanceof WaypointerScreen, 100);
        double[] position = context.computeOnClient(client -> {
            EditBox search = search((WaypointerScreen) MinecraftCompat.screen(client));
            double scale = client.getWindow().getGuiScale();
            return new double[]{(search.getX() + search.getWidth() / 2.0) * scale,
                    (search.getY() + search.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().typeChars("route");
        expectSearch(context, "route");
        context.getInput().pressKey(InputCompat.KEY_U);
        context.getInput().typeChar('u');
        expectSearch(context, "routeu");
        GameTestCompat.selectAll(context);
        context.getInput().typeChars("quartz");
        expectSearch(context, "quartz");
        context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        expectSearch(context, "quart");
        context.getInput().pressKey(InputCompat.KEY_HOME);
        context.getInput().pressKey(InputCompat.KEY_DELETE);
        expectSearch(context, "uart");
        context.takeScreenshot("route-search-keyboard-input");
        context.getInput().pressKey(InputCompat.KEY_ESCAPE);
        context.waitForScreen(null);
    }

    private static void expectSearch(ClientGameTestContext context, String expected) {
        context.waitFor(client -> MinecraftCompat.screen(client) instanceof WaypointerScreen screen
                && search(screen).isFocused() && expected.equals(search(screen).getValue()), 40);
    }

    private static EditBox search(WaypointerScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).findFirst().orElseThrow();
    }
}
