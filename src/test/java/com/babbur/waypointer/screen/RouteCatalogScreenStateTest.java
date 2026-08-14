package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogInstallRegistry;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogPublishResult;
import com.babbur.waypointer.catalog.CatalogRouteDetails;
import com.babbur.waypointer.catalog.CatalogRouteInstaller;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.api.ImportSource;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.api.DefaultWaypointerApi;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCatalogScreenStateTest {

    private static Field minecraftInstanceField;

    @BeforeAll
    static void installHeadlessMinecraftInstance() throws Exception {
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) {
                minecraftInstanceField = field;
                break;
            }
        }
        if (minecraftInstanceField == null) {
            throw new AssertionError("Minecraft singleton field was not found");
        }
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        Object minecraft = allocate.invoke(unsafe, Minecraft.class);
        minecraftInstanceField.setAccessible(true);
        minecraftInstanceField.set(null, minecraft);
    }

    @AfterAll
    static void removeHeadlessMinecraftInstance() throws Exception {
        minecraftInstanceField.set(null, null);
    }

    @TempDir
    Path tempDir;

    @Test
    void catalogPageStateCoversEmptyCursorCountAppendAndSelection() {
        RouteCatalogScreen screen = catalogScreen();

        invoke(screen, "applyPage", new Class<?>[]{CatalogPage.class, boolean.class},
                new CatalogPage(List.of(), false, null), false);
        assertTrue(componentField(screen, "statusText").getString().contains("empty"));

        set(screen, "searchQuery", "crystal");
        invoke(screen, "applyPage", new Class<?>[]{CatalogPage.class, boolean.class},
                new CatalogPage(List.of(), false, null), false);
        assertTrue(componentField(screen, "statusText").getString().contains("search"));

        CatalogRouteSummary first = summary("AAAAAAAAAAAAAAAAAAAAAA", "First", 2, 1);
        CatalogRouteSummary second = summary("BBBBBBBBBBBBBBBBBBBBBB", "Second", 4, 2);
        set(screen, "searchQuery", "");
        invoke(screen, "applyPage", new Class<?>[]{CatalogPage.class, boolean.class},
                new CatalogPage(List.of(first), true, "next_page"), false);
        assertEquals("next_page", field(screen, "nextCursor"));
        assertNull(invoke(screen, "selectedRoute", new Class<?>[0]));

        set(screen, "selectedRouteId", first.id());
        assertSame(first, invoke(screen, "selectedRoute", new Class<?>[0]));
        invoke(screen, "applyPage", new Class<?>[]{CatalogPage.class, boolean.class},
                new CatalogPage(List.of(first, second), false, null), true);
        assertEquals(List.of(first, second), field(screen, "routes"));
        assertNull(field(screen, "nextCursor"));

        CatalogRouteSummary replacement = summary(first.id(), "Replacement", 3, 1);
        invoke(screen, "applyPage", new Class<?>[]{CatalogPage.class, boolean.class},
                new CatalogPage(List.of(replacement), false, "ignored"), true);
        List<?> combined = (List<?>) field(screen, "routes");
        assertEquals("Replacement", CatalogRouteSummary.class.cast(combined.get(0)).title());

        set(screen, "selectedRouteId", "CCCCCCCCCCCCCCCCCCCCCC");
        invoke(screen, "reconcileSelection", new Class<?>[0]);
        assertNull(field(screen, "selectedRouteId"));
        assertNull(invoke(screen, "selectedRoute", new Class<?>[0]));
        invoke(screen, "reconcileSelection", new Class<?>[0]);
    }

    @Test
    void catalogDetailsMustMatchEverySummaryField() {
        RouteCatalogScreen screen = catalogScreen();
        CatalogRouteSummary requested = summary("AAAAAAAAAAAAAAAAAAAAAA", "Route", 5, 2);
        CatalogRouteDetails valid = new CatalogRouteDetails(requested, "WP:test");
        assertSame(valid, invoke(screen, "validateDetails",
                new Class<?>[]{CatalogRouteSummary.class, CatalogRouteDetails.class},
                requested, valid));

        assertMismatch(screen, requested, summary("BBBBBBBBBBBBBBBBBBBBBB", "Route", 5, 2));
        assertMismatch(screen, requested, summary(requested.id(), "Route", 6, 2));
        assertMismatch(screen, requested, summary(requested.id(), "Route", 5, 3));
        CatalogRouteSummary zoneMismatch = new CatalogRouteSummary(
                requested.id(), requested.title(), requested.description(), requested.authorName(),
                requested.publisherId(), requested.publisherVerified(), requested.visibility(),
                "hub", "Hub", requested.waypointCount(), requested.groupCount(), 9, 1, 0,
                requested.createdAt(), requested.updatedAt(), requested.sharePath());
        assertMismatch(screen, requested, zoneMismatch);
        CatalogRouteSummary codecMismatch = new CatalogRouteSummary(
                requested.id(), requested.title(), requested.description(), requested.authorName(),
                requested.publisherId(), requested.publisherVerified(), requested.visibility(),
                requested.zoneId(), requested.zoneLabel(), requested.waypointCount(),
                requested.groupCount(), 8, requested.version(), requested.downloads(),
                requested.createdAt(), requested.updatedAt(), requested.sharePath());
        assertMismatch(screen, requested, codecMismatch);
        CatalogRouteSummary versionMismatch = new CatalogRouteSummary(
                requested.id(), requested.title(), requested.description(), requested.authorName(),
                requested.publisherId(), requested.publisherVerified(), requested.visibility(),
                requested.zoneId(), requested.zoneLabel(), requested.waypointCount(),
                requested.groupCount(), requested.codecVersion(), requested.version() + 1,
                requested.downloads(), requested.createdAt(), requested.updatedAt(),
                requested.sharePath());
        assertMismatch(screen, requested, versionMismatch);
    }

    @Test
    void catalogLayoutCountersNarrationAndErrorsCoverAllStates() throws Exception {
        RouteCatalogScreen screen = catalogScreen();
        CatalogRouteSummary route = summary("AAAAAAAAAAAAAAAAAAAAAA", "Route", 5, 2);
        set(screen, "routes", List.of(route));
        set(screen, "listH", 90);

        assertEquals(2, invoke(screen, "visibleRowCount", new Class<?>[0]));
        set(screen, "nextCursor", "next");
        assertEquals(1, invoke(screen, "visibleRowCount", new Class<?>[0]));
        set(screen, "nextCursor", null);
        set(screen, "appending", true);
        assertEquals(1, invoke(screen, "visibleRowCount", new Class<?>[0]));

        set(screen, "scrollOffset", 2);
        invoke(screen, "scrollIntoView", new Class<?>[]{int.class, int.class}, 0, 10);
        assertEquals(0, field(screen, "scrollOffset"));
        invoke(screen, "scrollIntoView", new Class<?>[]{int.class, int.class}, 8, 10);
        assertTrue((int) field(screen, "scrollOffset") > 0);
        invoke(screen, "scrollIntoView", new Class<?>[]{int.class, int.class}, 8, 2);

        assertFalse(screen.isPauseScreen());
        assertTrue(screen.getNarrationMessage().getString().contains("route_catalog"));
        set(screen, "selectedRouteId", route.id());
        assertFalse(screen.getNarrationMessage().getString().isBlank());

        assertTrue(component(screen, "installButtonLabel").getString().contains("install"));
        HashSet.class.getMethod("add", Object.class)
                .invoke(field(screen, "installedRouteIds"), route.id());
        assertTrue(component(screen, "installButtonLabel").getString().contains("installed"));

        for (String method : List.of("waypointCount", "installCount", "routeCount", "groupCount")) {
            assertTrue(staticComponent(RouteCatalogScreen.class, method, 1L).getString().contains("one"));
            assertTrue(staticComponent(RouteCatalogScreen.class, method, 2L).getString().contains("many"));
        }
        assertEquals("Named", staticString(RouteCatalogScreen.class, "displayGroupName",
                new Class<?>[]{WaypointGroup.class}, group(" Named ", false, false)));
        assertTrue(staticString(RouteCatalogScreen.class, "displayGroupName",
                new Class<?>[]{WaypointGroup.class}, group(" ", false, false)).contains("unnamed"));

        for (String code : List.of("not_found", "rate_limited", "invalid_cursor",
                "route_id_mismatch", "payload_too_large", "request_too_large",
                "route_too_large", "publishing_disabled", "unknown")) {
            Component message = staticFailure(apiError(code));
            assertFalse(message.getString().isBlank());
        }
        assertFalse(staticFailure(new CompletionException(apiError("not_found"))).getString().isBlank());
        assertFalse(staticFailure(new ExecutionException(apiError("rate_limited"))).getString().isBlank());
        assertFalse(staticFailure(new IllegalStateException("offline")).getString().isBlank());
    }

    @Test
    void manualRefreshCooldownUsesAnExactTenSecondBoundary() {
        long tenSeconds = 10_000_000_000L;
        assertEquals(0, RouteCatalogScreen.refreshCooldownSeconds(false, 0, tenSeconds));
        assertEquals(10, RouteCatalogScreen.refreshCooldownSeconds(true, 0, tenSeconds));
        assertEquals(1, RouteCatalogScreen.refreshCooldownSeconds(
                true, tenSeconds - 1_000_000, tenSeconds));
        assertEquals(0, RouteCatalogScreen.refreshCooldownSeconds(
                true, tenSeconds, tenSeconds));
    }

    @Test
    void installedFocusUsesTheFirstImportedGroupThatStillExists() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = group("First", false, false);
        WaypointGroup second = group("Second", false, false);
        manager.addAll(List.of(first, second));

        ImportSummary summary = new ImportSummary(
                ImportSource.WAYPOINTER, "Catalog", 2, 0,
                List.of("missing", first.id(), second.id()));

        assertSame(first, RouteCatalogScreen.installedFocus(manager, summary));
        assertNull(RouteCatalogScreen.installedFocus(manager,
                new ImportSummary(ImportSource.WAYPOINTER, "Catalog", 0, 0,
                        List.of("missing"))));
    }

    @Test
    void staleClosedAndChangedSelectionInstallsCannotCommit() {
        WaypointGroup route = group("Catalog route", false, false);
        route.setZoneId("hub");
        route.add(new Waypoint(1, 64, 2, "Start", 0x4FE05A, 0, 0));
        String payload = WaypointCodec.encodeCatalog(List.of(route));
        CatalogRouteSummary routeSummary = new CatalogRouteSummary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Catalog route", "A useful route description.",
                "Tester", "wp_publisher", true, "unlisted", "hub", "Hub",
                1, 1, 9, 1, 0, "2026-08-12", "2026-08-12",
                "/r/AAAAAAAAAAAAAAAAAAAAAA");
        CatalogRouteInstaller.PreparedRoute prepared = CatalogRouteInstaller.prepare(
                new CatalogRouteDetails(routeSummary, payload));

        assertInstallDoesNotCommit(prepared, false, 1,
                routeSummary, routeSummary, 1);
        assertInstallDoesNotCommit(prepared, true, 1,
                routeSummary, summary("BBBBBBBBBBBBBBBBBBBBBB", "Other", 1, 1), 1);
        assertInstallDoesNotCommit(prepared, true, 2,
                routeSummary, routeSummary, 1);

        CatalogRouteSummary changedVersion = new CatalogRouteSummary(
                routeSummary.id(), routeSummary.title(), routeSummary.description(),
                routeSummary.authorName(), routeSummary.publisherId(),
                routeSummary.publisherVerified(), routeSummary.visibility(),
                routeSummary.zoneId(), routeSummary.zoneLabel(),
                routeSummary.waypointCount(), routeSummary.groupCount(),
                routeSummary.codecVersion(), routeSummary.version() + 1,
                routeSummary.downloads(), routeSummary.createdAt(),
                routeSummary.updatedAt(), routeSummary.sharePath());
        assertInstallDoesNotCommit(prepared, true, 1,
                routeSummary, changedVersion, 1);
    }

    @Test
    void publisherValidationLabelsAndNarrationCoverAllFormStates() {
        WaypointGroup group = group("Route", false, false);
        RoutePublishScreen screen = publishScreen(group);

        assertKey(screen, "validationHint", "empty_route");
        group.add(new Waypoint(1, 64, 2, "Start", 0x4FE05A, 0, 0));

        group.setTemp(true);
        assertKey(screen, "validationHint", "temporary");
        group.setTemp(false);
        group.setRuntimeOnly(true);
        assertKey(screen, "validationHint", "temporary");
        group.setRuntimeOnly(false);

        set(screen, "titleValue", null);
        assertKey(screen, "validationHint", "title_required");
        set(screen, "titleValue", "   ");
        assertKey(screen, "validationHint", "title_required");
        set(screen, "titleValue", "Published route");

        set(screen, "descriptionValue", null);
        assertKey(screen, "validationHint", "description_min");
        set(screen, "descriptionValue", "123456789");
        assertKey(screen, "validationHint", "description_min");
        set(screen, "descriptionValue", "x".repeat(501));
        assertKey(screen, "validationHint", "description_max");
        set(screen, "descriptionValue", "A useful route description.");

        assertNull(invoke(screen, "validationHint", new Class<?>[0]));
        assertEquals(true, invoke(screen, "validInput", new Class<?>[0]));

        assertFalse(component(screen, "visibilityOption",
                new Class<?>[]{CatalogPublishRequest.Visibility.class},
                CatalogPublishRequest.Visibility.UNLISTED).getString().startsWith("["));
        assertFalse(component(screen, "visibilityOption",
                new Class<?>[]{CatalogPublishRequest.Visibility.class},
                CatalogPublishRequest.Visibility.PUBLIC).getString().startsWith("["));
        assertTrue(component(screen, "visibilityHelp").getString().contains("unlisted"));
        set(screen, "visibility", CatalogPublishRequest.Visibility.PUBLIC);
        assertTrue(component(screen, "visibilityHelp").getString().contains("public"));

        assertTrue(component(screen, "publishButtonLabel").getString().contains("publish"));
        set(screen, "publishing", true);
        assertTrue(component(screen, "publishButtonLabel").getString().contains("publishing"));
        set(screen, "publishing", false);
        set(screen, "result", new CatalogPublishResult(summary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Route", 1, 1), "token"));
        assertTrue(component(screen, "publishButtonLabel").getString().contains("again"));

        set(screen, "titleValue", null);
        assertTrue(((String) invoke(screen, "previewName", new Class<?>[0])).contains("Route"));
        set(screen, "titleValue", " Preview title ");
        assertEquals("Preview title", invoke(screen, "previewName", new Class<?>[0]));
        assertFalse(screen.isPauseScreen());
        assertTrue(screen.getNarrationMessage().getString().contains("route_publish"));
    }

    @Test
    void publisherStatusCardsAndControlDefaultsCoverEveryBranch() throws Exception {
        WaypointGroup group = group("", false, false);
        group.add(new Waypoint(1, 64, 2, "Start", 0x4FE05A, 0, 0));
        RoutePublishScreen screen = publishScreen(group);

        set(screen, "showDetail", false);
        set(screen, "showCard", false);
        set(screen, "result", null);
        assertEquals(50, invoke(screen, "cardHeight", new Class<?>[0]));
        int compact = (int) invoke(screen, "formHeight", new Class<?>[0]);
        set(screen, "showDetail", true);
        set(screen, "showCard", true);
        set(screen, "result", new CatalogPublishResult(summary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Route", 1, 1), "token"));
        assertTrue((int) invoke(screen, "formHeight", new Class<?>[0]) > compact);
        assertEquals(60, invoke(screen, "cardHeight", new Class<?>[0]));

        Class<?> statusKind = Class.forName(
                "com.babbur.waypointer.screen.RoutePublishScreen$StatusKind");
        for (Object kind : statusKind.getEnumConstants()) {
            set(screen, "statusKind", kind);
            set(screen, "statusText", Component.literal("Status"));
            assertTrue((int) invoke(screen, "statusColor", new Class<?>[0]) != 0);
            assertTrue(invoke(screen, "statusMarker", new Class<?>[0]) instanceof String);
        }
        set(screen, "statusText", Component.empty());
        assertEquals("", invoke(screen, "statusMarker", new Class<?>[0]));
        assertEquals(false, invoke(screen, "anyEditBoxFocused", new Class<?>[0]));

        set(screen, "result", null);
        set(screen, "publishedPayload", "WP:test");
        set(screen, "statusText", Component.literal("stale"));
        invoke(screen, "onFormEdited", new Class<?>[0]);
        assertNull(field(screen, "result"));
        assertNull(field(screen, "publishedPayload"));
        assertTrue(componentField(screen, "statusText").getString().isBlank());
    }

    @Test
    void publisherErrorsUseStableLocalizedMessages() throws Exception {
        for (String code : List.of(
                "publishing_disabled", "rate_limited", "duplicate_route", "empty_route",
                "zone_required", "payload_too_large", "invalid_route",
                "invalid_publisher_name", "publisher_name_required",
                "publisher_name_taken", "publisher_name_locked", "invalid_signature",
                "unknown")) {
            Component message = (Component) invokeStatic(
                    RoutePublishScreen.class, "friendlyFailure",
                    new Class<?>[]{Throwable.class}, apiError(code));
            assertFalse(message.getString().isBlank(), code);
        }
        Component wrapped = (Component) invokeStatic(
                RoutePublishScreen.class, "friendlyFailure",
                new Class<?>[]{Throwable.class},
                new CompletionException(apiError("publisher_name_taken")));
        assertFalse(wrapped.getString().isBlank());
        Component offline = (Component) invokeStatic(
                RoutePublishScreen.class, "friendlyFailure",
                new Class<?>[]{Throwable.class}, new IllegalStateException("offline"));
        assertFalse(offline.getString().isBlank());
    }

    private RouteCatalogScreen catalogScreen() {
        return new RouteCatalogScreen(null, new RouteCatalogClient("test"),
                new DefaultWaypointerApi(new ActiveGroupManager()),
                new CatalogInstallRegistry(tempDir.resolve(
                        "catalog-installs-" + System.nanoTime() + ".json")));
    }

    private void assertInstallDoesNotCommit(
            CatalogRouteInstaller.PreparedRoute prepared,
            boolean screenActive,
            int currentGeneration,
            CatalogRouteSummary requestedRoute,
            CatalogRouteSummary selectedRoute,
            int completedGeneration) {
        ActiveGroupManager manager = new ActiveGroupManager();
        RouteCatalogScreen screen = new RouteCatalogScreen(
                null, new RouteCatalogClient("test"), new DefaultWaypointerApi(manager),
                new CatalogInstallRegistry(tempDir.resolve(
                        "stale-install-" + System.nanoTime() + ".json")));
        set(screen, "screenActive", screenActive);
        set(screen, "detailGeneration", currentGeneration);
        set(screen, "routes", List.of(selectedRoute));
        set(screen, "selectedRouteId", selectedRoute.id());
        set(screen, "installAttempt", 7L);
        set(screen, "detailLoading", true);

        invoke(screen, "finishInstall", new Class<?>[]{
                        long.class, int.class, CatalogRouteSummary.class,
                        CatalogRouteInstaller.PreparedRoute.class, Throwable.class},
                7L, completedGeneration, requestedRoute, prepared, null);

        assertTrue(manager.allGroups().isEmpty());
        assertFalse((boolean) field(screen, "detailLoading"));
    }

    private RoutePublishScreen publishScreen(WaypointGroup group) {
        return new RoutePublishScreen(null, new WaypointerConfig(), group,
                new RouteCatalogClient("test"),
                new PublisherIdentityStore(tempDir.resolve("identity.json")));
    }

    private static WaypointGroup group(String name, boolean temp, boolean runtime) {
        WaypointGroup group = new WaypointGroup("test-" + System.nanoTime(), name, "glacite_tunnels");
        group.setTemp(temp);
        group.setRuntimeOnly(runtime);
        return group;
    }

    private static CatalogRouteSummary summary(
            String id, String title, int waypoints, int groups) {
        return new CatalogRouteSummary(id, title, "A useful route description.", "Tester",
                "wp_publisher", true, "unlisted", "glacite_tunnels", "Glacite Tunnels",
                waypoints, groups, 9, 1, 0, "2026-08-12", "2026-08-12", "/r/" + id);
    }

    private static void assertMismatch(
            RouteCatalogScreen screen, CatalogRouteSummary requested,
            CatalogRouteSummary actual) {
        assertThrows(IllegalArgumentException.class, () -> invoke(screen, "validateDetails",
                new Class<?>[]{CatalogRouteSummary.class, CatalogRouteDetails.class},
                requested, new CatalogRouteDetails(actual, "WP:test")));
    }

    private static void assertKey(Object target, String method, String key) {
        assertTrue(component(target, method).getString().contains(key));
    }

    private static CatalogApiException apiError(String code) throws Exception {
        Constructor<CatalogApiException> constructor = CatalogApiException.class
                .getDeclaredConstructor(int.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(400, code, "failure");
    }

    private static Component staticFailure(Throwable failure) {
        return (Component) invokeStatic(RouteCatalogScreen.class, "friendlyFailure",
                new Class<?>[]{Throwable.class}, failure);
    }

    private static Component staticComponent(Class<?> type, String method, long value) {
        return (Component) invokeStatic(type, method, new Class<?>[]{long.class}, value);
    }

    private static String staticString(
            Class<?> type, String method, Class<?>[] parameterTypes, Object... arguments) {
        return (String) invokeStatic(type, method, parameterTypes, arguments);
    }

    private static Component component(Object target, String method) {
        return (Component) invoke(target, method, new Class<?>[0]);
    }

    private static Component component(
            Object target, String method, Class<?>[] parameterTypes, Object... arguments) {
        return (Component) invoke(target, method, parameterTypes, arguments);
    }

    private static Component componentField(Object target, String name) {
        return (Component) field(target, name);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object invoke(
            Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object invokeStatic(
            Class<?> type, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
