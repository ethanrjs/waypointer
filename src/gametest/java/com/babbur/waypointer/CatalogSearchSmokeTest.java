package com.babbur.waypointer;

import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.screen.RouteCatalogScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/** Exercises the actual EditBox responder ordering that crashed player searches. */
@SuppressWarnings("UnstableApiUsage")
final class CatalogSearchSmokeTest {
    static void run(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen = new RouteCatalogScreen(null, RouteCatalogClient.production());
            try {
                field(screen, "listRequested", true); // An already loaded catalog: no network in this check.
                @SuppressWarnings("unchecked")
                var cache = (Map<String, CatalogPage>) field(screen, "searchCache");
                cache.put("\n", new CatalogPage(List.of(new CatalogRouteSummary(
                        "AAAAAAAAAAAAAAAAAAAAAA", "Route", "", "Tester", "", false,
                        "public", "hub", "Hub", 44, 1, 10, 1, 0, "", "", "/r/test")), false, null));
                MinecraftCompat.setScreen(client, screen);
                var hideInstalled = (net.minecraft.client.gui.components.AbstractWidget) field(screen, "hideInstalledChip");
                if (hideInstalled == null || hideInstalled.getY() != (int) field(screen, "chipsY"))
                    throw new AssertionError("Hide installed must sit in the filter chip row");
                for (int i = 0; i < 100; i++) {
                    EditBox search = (EditBox) field(screen, "searchBox");
                    screen.setFocused(search);
                    search.setFocused(true);
                    search.setValue("te");
                    if (!((Component) field(screen, "statusText")).getString().isEmpty())
                        throw new AssertionError("Typing should not show a waiting message");
                    search.setCursorPosition(2);
                    search.setHighlightPos(2);
                    search.deleteCharsToPos(1); // Previously getHighlighted() read [1,2) of "t" here.
                    if (field(screen, "searchBox") != search)
                        throw new AssertionError("Search rebuilt during EditBox mutation");
                    screen.tick();
                    search = (EditBox) field(screen, "searchBox");
                    if (!search.getValue().equals("t") || search.getCursorPosition() != 1 || !search.isFocused())
                        throw new AssertionError("Search text/caret/focus lost after deletion");
                    search.setHighlightPos(0);
                    search.insertText("");
                    search.insertText("Tester");
                    screen.tick();
                    if ((boolean) field(screen, "listLoading") || (int) field(screen, "searchDebounceTicks") != 0)
                        throw new AssertionError("Complete cached catalog should search immediately without HTTP");
                }
            } finally {
                MinecraftCompat.setScreen(client, null);
            }
        });
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void field(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
