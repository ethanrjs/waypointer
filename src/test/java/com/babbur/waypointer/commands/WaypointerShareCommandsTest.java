package com.babbur.waypointer.commands;

import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerShareCommandsTest {

    @Test
    void defaultExportDoesNotSelectTheWholeLibraryWithoutAnActiveZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        for (int index = 0; index < 219; index++) {
            manager.add(WaypointGroup.create("Library " + index, "room-" + index));
        }
        CapturingScheduler scheduler = new CapturingScheduler();
        CommandMessages messages = new CommandMessages();
        WaypointerShareCommands commands = commands(manager, scheduler);

        assertEquals(0, commands.runExport(
                messages.source(), WaypointCodec.Options.WITH_NAMES));

        assertEquals(0, scheduler.calls);
        assertEquals(List.of("waypointer.command.export.no_active_route"),
                messages.feedbackKeys());
    }

    @Test
    void activeRegularRouteShowsFeedbackBeforeItIsScheduled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup active = WaypointGroup.create("Active", "hub");
        active.add(Waypoint.at(1, 70, 2));
        manager.add(active);
        manager.add(WaypointGroup.create("Other", "the_end"));
        List<String> events = new ArrayList<>();
        CapturingScheduler scheduler = new CapturingScheduler(events);
        CommandMessages messages = new CommandMessages(events);
        WaypointerShareCommands commands = commands(manager, scheduler);

        assertEquals(1, commands.runExport(
                messages.source(), WaypointCodec.Options.NO_NAMES));

        assertEquals(List.of(
                "feedback:waypointer.command.export.exporting", "scheduled"), events);
        assertEquals(1, scheduler.groups.size());
        assertEquals(active.id(), scheduler.groups.getFirst().id());
        assertEquals("Active", scheduler.groups.getFirst().name());
        assertNotSame(active, scheduler.groups.getFirst(),
                "codec work must receive an export snapshot");
        assertSame(WaypointCodec.Options.NO_NAMES, scheduler.options);
    }

    @Test
    void exportCapturesLibraryMetadataBeforeCreatingCodecSnapshots() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup active = WaypointGroup.create("Active", "hub");
        active.add(Waypoint.at(1, 70, 2).withColor(0x112233));
        active.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        active.set(0, active.get(0).withColor(0xAABBCC));
        active.setGradientMode(WaypointGroup.GradientMode.STATIC);
        manager.add(active);
        manager.addFolder(new RouteFolder(
                "source-folder", "Mining", "hub", true, 0x2468AC),
                List.of(active.id()));
        CapturingScheduler scheduler = new CapturingScheduler();

        assertEquals(1, commands(manager, scheduler).runExport(
                new CommandMessages().source(), WaypointCodec.Options.FULL_FIDELITY));

        assertEquals(1, scheduler.metadata.manualColors().size());
        assertEquals(List.of(0), scheduler.metadata.folders().getFirst().memberOrdinals());

        String payload = UniversalShareCodec.encodeWaypoints(
                scheduler.groups, scheduler.options, scheduler.metadata);
        assertTrue(payload.startsWith("WPL:1:"));
        assertFalse(UniversalShareCodec.decode(payload) == null);
    }

    @Test
    void commandImportInstallsDecodedFoldersAfterRoutes() {
        ActiveGroupManager source = new ActiveGroupManager();
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3).withColor(0x112233));
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.set(0, route.get(0).withColor(0xABCDEF));
        route.setStaticColor(0x2468AC);
        route.setGradientMode(WaypointGroup.GradientMode.STATIC);
        source.add(route);
        source.addFolder(new RouteFolder(
                "source-folder", "Imported", "hub", true, 0x13579B),
                List.of(route.id()));
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(source, List.of(route));
        String payload = UniversalShareCodec.encodeWaypoints(
                List.of(route.exportSnapshot()), WaypointCodec.Options.FULL_FIDELITY, metadata);

        ActiveGroupManager target = new ActiveGroupManager();
        WaypointerShareCommands commands = commands(target, new CapturingScheduler());
        commands.finishImport(
                new CommandMessages().source(), WaypointCommandImport.decode(payload),
                "argument", Zone.fromId("hub"));

        WaypointGroup imported = target.allGroupsList().getFirst();
        assertEquals(0x2468AC, imported.get(0).color());
        assertEquals(0xABCDEF, imported.manualColorSnapshot().getFirst());
        RouteFolder folder = target.folderForGroup(imported.id());
        assertEquals("Imported", folder.name());
        assertEquals(0x13579B, folder.color());
        assertTrue(folder.collapsed());
        assertNotEquals("source-folder", folder.id());
    }

    @Test
    void generatedDungeonRouteMapsOnlyToItsExactDurableSource() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup stored = new WaypointGroup("stored", "Stored", "room-a");
        stored.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        stored.add(Waypoint.at(1, 2, 3));
        WaypointGroup generated = new WaypointGroup(
                "dungeon:auto:room-a", "Generated", "room-a");
        generated.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        generated.setRuntimeOnly(true);
        generated.setRuntimeSourceGroupId(stored.id());
        generated.add(Waypoint.at(10, 20, 30));
        manager.addAll(List.of(stored, generated));
        manager.onZoneChanged(new Zone("room-a", "Room A"));

        assertEquals(List.of(stored), WaypointerShareCommands.cliExportGroups(manager));

        generated.setRuntimeSourceGroupId("missing-source");
        assertTrue(WaypointerShareCommands.cliExportGroups(manager).isEmpty());
    }

    @Test
    void focusedTemporaryRouteIsNotExportedAsTheActiveRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup saved = WaypointGroup.create("Saved", "hub");
        saved.add(Waypoint.at(1, 2, 3));
        WaypointGroup temporary = new WaypointGroup("temp", "Temporary", "hub");
        temporary.setTemp(true);
        temporary.add(Waypoint.at(4, 5, 6)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.addAll(List.of(saved, temporary));
        manager.focusTempWaypoint(temporary, 0);

        assertTrue(WaypointerShareCommands.cliExportGroups(manager).isEmpty());
    }

    @Test
    void emptyActiveRoutesAreSkippedForTheFirstUsableRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup empty = WaypointGroup.create("Empty", "hub");
        WaypointGroup usable = WaypointGroup.create("Usable", "hub");
        usable.add(Waypoint.at(1, 2, 3));
        manager.addAll(List.of(empty, usable));

        assertEquals(List.of(usable), WaypointerShareCommands.cliExportGroups(manager));

        manager.remove(usable.id());
        assertTrue(WaypointerShareCommands.cliExportGroups(manager).isEmpty());
    }

    @Test
    void bulkExportIncludesOnlySavedRegularRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup saved = WaypointGroup.create("Saved", "hub");
        WaypointGroup disabledSaved = WaypointGroup.create("Disabled", "hub");
        disabledSaved.setEnabled(false);
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "room-a");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup temporary = WaypointGroup.create("Temporary", "hub");
        temporary.setTemp(true);
        WaypointGroup runtime = WaypointGroup.create("Runtime", "hub");
        runtime.setRuntimeOnly(true);
        manager.addAll(List.of(saved, dungeon, temporary, runtime, disabledSaved));

        assertEquals(List.of(saved, disabledSaved),
                WaypointerShareCommands.cliBulkExportGroups(manager));
        assertEquals(List.of(saved, disabledSaved),
                WaypointerCommands.cliBulkExportGroups(manager));
    }

    @Test
    void bulkExportReportsWhenNoSavedRegularRoutesExist() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "room-a");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup temporary = WaypointGroup.create("Temporary", "hub");
        temporary.setTemp(true);
        manager.addAll(List.of(dungeon, temporary));
        CapturingScheduler scheduler = new CapturingScheduler();
        CommandMessages messages = new CommandMessages();

        assertEquals(0, commands(manager, scheduler).runExportRoutes(
                messages.source(), WaypointCodec.Options.WITH_NAMES));

        assertEquals(0, scheduler.calls);
        assertEquals(List.of("waypointer.command.export.no_bulk_routes"),
                messages.feedbackKeys());
    }

    private static WaypointerShareCommands commands(
            ActiveGroupManager manager, CapturingScheduler scheduler) {
        return new WaypointerShareCommands(
                manager, new WaypointerConfig(), new ChatImportCache(), scheduler);
    }

    private static String translationKey(Component component) {
        return assertInstanceOf(
                TranslatableContents.class, component.getContents()).getKey();
    }

    private static final class CapturingScheduler
            implements WaypointerShareCommands.RouteExportScheduler {
        private final List<String> events;
        private int calls;
        private List<WaypointGroup> groups;
        private WaypointCodec.Options options;
        private RouteLibraryMetadata metadata;

        private CapturingScheduler() {
            this(null);
        }

        private CapturingScheduler(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean schedule(
                List<WaypointGroup> groups,
                WaypointCodec.Options options,
                RouteLibraryMetadata metadata,
                Consumer<String> completion) {
            calls++;
            this.groups = groups;
            this.options = options;
            this.metadata = metadata;
            if (events != null) events.add("scheduled");
            return true;
        }
    }

    private static final class CommandMessages {
        private final List<Component> feedback = new ArrayList<>();
        private final List<Component> errors = new ArrayList<>();
        private final List<String> events;

        private CommandMessages() {
            this(null);
        }

        private CommandMessages(List<String> events) {
            this.events = events;
        }

        private FabricClientCommandSource source() {
            return (FabricClientCommandSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{FabricClientCommandSource.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sendFeedback" -> {
                            Component message = (Component) args[0];
                            feedback.add(message);
                            if (events != null) {
                                events.add("feedback:" + translationKey(message));
                            }
                            yield null;
                        }
                        case "sendError" -> {
                            errors.add((Component) args[0]);
                            yield null;
                        }
                        case "toString" -> "CommandMessagesSource";
                        default -> throw new AssertionError(
                                "Unexpected source call: " + method.getName());
                    });
        }

        private List<String> feedbackKeys() {
            return feedback.stream()
                    .map(WaypointerShareCommandsTest::translationKey)
                    .toList();
        }
    }
}
