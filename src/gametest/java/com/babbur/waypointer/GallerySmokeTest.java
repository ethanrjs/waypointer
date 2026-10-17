package com.babbur.waypointer;

import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.screen.PublishedRoutesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** Render the gallery with device-local synthetic records, without identity or network access. */
@SuppressWarnings("UnstableApiUsage")
final class GallerySmokeTest {
    static void run(ClientGameTestContext context) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                try {
                    var constructor = PublishedRoutesScreen.class.getDeclaredConstructor(Screen.class,
                            RouteCatalogClient.class, PublisherIdentityStore.class, CatalogPublicationRegistry.class);
                    constructor.setAccessible(true);
                    PublishedRoutesScreen screen = constructor.newInstance(null, RouteCatalogClient.production(), null, null);
                    set(screen, "loading", false);
                    set(screen, "status", Component.empty());
                    Object model = field(screen, "model");
                    var replace = model.getClass().getDeclaredMethod("replace", List.class);
                    replace.setAccessible(true);
                    replace.invoke(model, IntStream.range(0, 24).mapToObj(i -> new CatalogPublication(
                            "gallery-" + i, "tester", "Tester", "Mining route " + (i + 1),
                            "A route through the Crystal Hollows", CatalogPublishRequest.Visibility.PUBLIC,
                            "crystal_hollows", 1, 10, "2026-09-13T12:00:00Z", "/r/gallery-" + i,
                            "https://waypointermod.com/api", "", Instant.parse("2026-09-13T12:00:00Z"))).toList());
                    MinecraftCompat.setScreen(client, screen);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                PublishedRoutesScreen screen = (PublishedRoutesScreen) MinecraftCompat.screen(client);
                List<AbstractWidget> cards = cards(screen);
                if (cards.size() < 2) throw new AssertionError("My Routes should list several publications");
                if (cards.get(1).getX() != cards.getFirst().getX()
                        || cards.get(1).getY() <= cards.getFirst().getY())
                    throw new AssertionError("My Routes rows are not stacked in one list");
                assertBounds(screen, cards);
                AbstractWidget first = cards.getFirst();
                screen.mouseClicked(new MouseButtonEvent(first.getX() + 8, first.getY() + 8,
                        new MouseButtonInfo(0, 0)), false);
                if (!"gallery-0".equals(field(field(screen, "model"), "selectedRouteId")))
                    throw new AssertionError("My Routes row did not select its publication");
            });
            context.waitTicks(2);
            context.takeScreenshot("my-routes-gallery-wide");
            context.runOnClient(client -> {
                client.options.guiScale().set(4);
                client.resizeGui();
                PublishedRoutesScreen screen = (PublishedRoutesScreen) MinecraftCompat.screen(client);
                assertBounds(screen, cards(screen));
                if (!"gallery-0".equals(field(field(screen, "model"), "selectedRouteId")))
                    throw new AssertionError("Gallery resize lost the visible selection");
            });
            context.waitTicks(2);
            context.takeScreenshot("my-routes-gallery-compact");
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.options.guiScale().set(previousScale);
                client.resizeGui();
            });
        }
    }

    private static List<AbstractWidget> cards(PublishedRoutesScreen screen) {
        return screen.children().stream().filter(child -> child.getClass().getSimpleName().equals("Row"))
                .map(AbstractWidget.class::cast).toList();
    }

    private static void assertBounds(PublishedRoutesScreen screen, List<AbstractWidget> cards) {
        int left = (int) field(screen, "listX");
        int right = left + (int) field(screen, "listW");
        int bottom = (int) field(screen, "listY") + (int) field(screen, "listH");
        for (AbstractWidget card : cards) {
            if (card.getX() < left || card.getX() + card.getWidth() > right || card.getY() + card.getHeight() > bottom)
                throw new AssertionError("My Routes row leaves the list bounds");
        }
        if ((int) field(screen, "panelW") + 2 * (int) field(screen, "panelX") != screen.width)
            throw new AssertionError("My Routes should occupy the full screen with catalog margins");
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void set(Object owner, String name, Object value) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
