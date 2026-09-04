package com.babbur.waypointer.commands;

import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.codec.RouteLibraryCodec;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.screen.ConfigImportConfirmation;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.ClickEvent;
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
    void unmatchedDungeonChatImportExplainsWhyNothingWasInstalled() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, new WaypointerConfig(), new ChatImportCache());
        CommandMessages messages = new CommandMessages();
        DungeonRouteImporter.Result result = new DungeonRouteImporter.Result(
                List.of(), 0, List.of("Unmatched room"), 0,
                DungeonRouteImporter.Format.SECRET_ROUTES);

        assertEquals(0, commands.importDungeonRoutes(messages.source(), result, "chat"));

        assertEquals(List.of("waypointer.dungeon.command.import.no_usable_routes"),
                messages.errorKeys());
        assertEquals(List.of("waypointer.dungeon.command.import.unmatched_rooms"),
                messages.feedbackKeys());
        assertTrue(manager.allGroupsList().isEmpty());
    }

    @Test
    void partialDungeonChatImportReportsInstalledAndUnmatchedRooms() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, new WaypointerConfig(), new ChatImportCache());
        WaypointGroup route = WaypointGroup.create("Crypt", "crypt-a");
        route.add(Waypoint.at(1, 70, 2));
        CommandMessages messages = new CommandMessages();
        DungeonRouteImporter.Result result = new DungeonRouteImporter.Result(
                List.of(route), 1, List.of("Unmatched room"), 2,
                DungeonRouteImporter.Format.SECRET_ROUTES);

        assertEquals(1, commands.importDungeonRoutes(messages.source(), result, "chat"));

        assertTrue(messages.errorKeys().isEmpty());
        assertEquals(List.of(
                "waypointer.dungeon.command.import.success",
                "waypointer.dungeon.routes.existing_disabled",
                "waypointer.dungeon.command.import.skipped_variants",
                "waypointer.dungeon.command.import.unmatched_rooms",
                "waypointer.command.import.open_editor_hint"), messages.feedbackKeys());
        assertEquals(List.of(route), manager.allGroupsList());
    }

    @Test
    void typedChatConfigWaitsForConfirmationAndCancelDoesNotMutateAnything() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointerConfig current = new WaypointerConfig();
        WaypointerConfig imported = new WaypointerConfig();
        imported.setShowTracer(false);
        CapturingConfirmation confirmation = new CapturingConfirmation();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, current, new ChatImportCache(), new CapturingScheduler(), confirmation);
        CommandMessages messages = new CommandMessages();

        commands.finishImport(messages.source(), WaypointCommandImport.decode(
                        UniversalShareCodec.encodeConfig(imported)),
                "chat", manager.currentZone(), UniversalShareCodec.Type.CONFIG);

        assertEquals(1, confirmation.calls);
        assertTrue(current.showTracer(), "opening confirmation must not apply settings");
        assertTrue(manager.allGroupsList().isEmpty(),
                "config imports must never create current-zone routes");
        assertEquals(List.of("waypointer.command.import.config.review"),
                messages.feedbackKeys());

        confirmation.resolve(false);
        assertTrue(current.showTracer(), "cancel must leave live settings untouched");
        assertTrue(manager.allGroupsList().isEmpty());
        assertEquals(List.of(
                        "waypointer.command.import.config.review",
                        "waypointer.command.import.config.cancelled"),
                messages.feedbackKeys());
    }

    @Test
    void typedChatConfigAppliesOnlyAfterAffirmativeConfirmation() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig current = new WaypointerConfig();
        WaypointerConfig imported = new WaypointerConfig();
        imported.setShowTracer(false);
        CapturingConfirmation confirmation = new CapturingConfirmation();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, current, new ChatImportCache(), new CapturingScheduler(), confirmation);
        CommandMessages messages = new CommandMessages();

        commands.finishImport(messages.source(), WaypointCommandImport.decode(
                        UniversalShareCodec.encodeConfig(imported)),
                "chat", Zone.UNKNOWN, UniversalShareCodec.Type.CONFIG);
        assertTrue(current.showTracer());

        confirmation.resolve(true);
        assertFalse(current.showTracer());
        assertEquals("waypointer.command.import.config.imported.one",
                messages.feedbackKeys().getLast());
        assertTrue(manager.allGroupsList().isEmpty());
    }

    @Test
    void legacyWpcInteractiveImportUsesTheSameConfirmationGate() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig current = new WaypointerConfig();
        WaypointerConfig imported = new WaypointerConfig();
        imported.setShowTracer(false);
        CapturingConfirmation confirmation = new CapturingConfirmation();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, current, new ChatImportCache(), new CapturingScheduler(), confirmation);

        commands.finishImport(new CommandMessages().source(), WaypointCommandImport.decode(
                        WaypointerConfigCodec.encode(imported)),
                "clipboard", Zone.UNKNOWN);

        assertEquals(1, confirmation.calls);
        assertTrue(current.showTracer());
    }

    @Test
    void typedChatClickRejectsWrongShareTypeWithoutFallingThrough() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig current = new WaypointerConfig();
        CapturingConfirmation confirmation = new CapturingConfirmation();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, current, new ChatImportCache(), new CapturingScheduler(), confirmation);
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(1, 2, 3));
        CommandMessages messages = new CommandMessages();

        commands.finishImport(messages.source(), WaypointCommandImport.decode(
                        WaypointCodec.encode(List.of(route), WaypointCodec.Options.WITH_NAMES)),
                "chat", Zone.fromId("hub"), UniversalShareCodec.Type.CONFIG);

        assertEquals(0, confirmation.calls);
        assertTrue(manager.allGroupsList().isEmpty());
        assertEquals(List.of("waypointer.command.import.wrong_type"), messages.errorKeys());
    }

    @Test
    void typedRouteClickStillImportsRouteWithoutConfigConfirmation() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        CapturingConfirmation confirmation = new CapturingConfirmation();
        WaypointerShareCommands commands = new WaypointerShareCommands(
                manager, new WaypointerConfig(), new ChatImportCache(),
                new CapturingScheduler(), confirmation);
        WaypointGroup route = WaypointGroup.create("Route", Zone.UNKNOWN.id());
        route.add(Waypoint.at(1, 2, 3));

        commands.finishImport(new CommandMessages().source(), WaypointCommandImport.decode(
                        WaypointCodec.encode(List.of(route), WaypointCodec.Options.WITH_NAMES)),
                "chat", manager.currentZone(), UniversalShareCodec.Type.WAYPOINTS);

        assertEquals(0, confirmation.calls);
        assertEquals(1, manager.allGroupsList().size());
        assertEquals("hub", manager.allGroupsList().getFirst().zoneId());
    }

    @Test
    void dungeonCommandEncoderUsesUniversalKind4Payload() {
        WaypointGroup route = WaypointGroup.create("Crypt", "crypt-a");
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.add(Waypoint.at(1, 70, 2));

        String payload = UniversalShareCodec.encodeDungeon(List.of(route));

        assertTrue(payload.startsWith("WP:") && WaypointCodec.debugDecode(payload).version() == 10);
        assertInstanceOf(UniversalShareCodec.DungeonRoutes.class,
                UniversalShareCodec.decode(payload));
    }

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
    void freshConfigDefaultExportUsesTheV10BareCoordinatePath() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup active = WaypointGroup.create("Decorated route", "hub");
        active.add(Waypoint.at(1, 70, 2).withColor(0x112233));
        active.add(Waypoint.at(9, 71, -4).withColor(0xAABBCC));
        manager.add(active);
        CapturingScheduler scheduler = new CapturingScheduler();
        WaypointerShareCommands commands = commands(manager, scheduler);

        WaypointCodec.Options defaults = commands.exportOptionsFromConfig();
        assertFalse(defaults.isBareCoordinateProjection());
        assertEquals(1, commands.runExport(new CommandMessages().source(), defaults));
        assertTrue(scheduler.options.isBareCoordinateProjection());

        String payload = UniversalShareCodec.encodeWaypoints(
                scheduler.groups, scheduler.options, scheduler.metadata);
        assertTrue(payload.startsWith("WP:") && WaypointCodec.debugDecode(payload).version() == 10);
        List<WaypointGroup> decoded = WaypointCodec.decode(payload);
        assertEquals(1, decoded.size());
        assertEquals(List.of(
                        Waypoint.at(1, 70, 2),
                        Waypoint.at(9, 71, -4)),
                decoded.getFirst().waypoints());
    }

    @Test
    void freshConfigBulkCommandExportUsesKind6ThroughTheProductFacade() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        WaypointGroup first = WaypointGroup.create("Discarded first", "hub");
        first.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        first.add(Waypoint.at(1, 70, 2).withColor(0x112233));
        WaypointGroup second = WaypointGroup.create("Discarded second", "hub");
        second.add(Waypoint.at(9, 71, -4).withColor(0xAABBCC));
        manager.addAll(List.of(first, second));
        CapturingScheduler scheduler = new CapturingScheduler();
        WaypointerShareCommands commands = commands(manager, scheduler);
        WaypointCodec.Options defaults = commands.exportOptionsFromConfig();

        assertFalse(defaults.isBareCoordinateProjection());
        assertEquals(2, commands.runExportRoutes(new CommandMessages().source(), defaults));
        assertTrue(scheduler.options.isBareCoordinateProjection());
        String payload = UniversalShareCodec.encodeWaypoints(
                scheduler.groups, scheduler.options, scheduler.metadata);
        var debug = WaypointCodec.debugDecode(payload);

        assertEquals(10, debug.version());
        assertTrue(debug.groups().stream().allMatch(group ->
                group.coordMode().startsWith("V10_BARE_PACK_")));
        assertFalse(payload.startsWith(RouteLibraryCodec.MAGIC),
                "all-off options must filter captured metadata before kind6 selection");
    }

    @Test
    void freshConfigActiveDungeonExportRemainsOnTheNonBareGeneralPath() {
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
        CapturingScheduler scheduler = new CapturingScheduler();
        WaypointerShareCommands commands = commands(manager, scheduler);

        WaypointCodec.Options defaults = commands.exportOptionsFromConfig();
        assertFalse(defaults.isBareCoordinateProjection());
        assertEquals(1, commands.runExport(new CommandMessages().source(), defaults));
        assertFalse(scheduler.options.isBareCoordinateProjection());

        String payload = UniversalShareCodec.encodeWaypoints(
                scheduler.groups, scheduler.options, scheduler.metadata);
        assertTrue(WaypointCodec.debugDecode(payload).groups().stream().noneMatch(group ->
                group.coordMode().startsWith("V10_BARE")));
        assertInstanceOf(UniversalShareCodec.Waypoints.class,
                UniversalShareCodec.decode(payload));
    }

    @Test
    void bareExportFeedbackExplainsTheCoordinateOnlyProjection() {
        Component message = WaypointerShareCommands.exportSuccessMessage(
                1, "WP:Apayload", WaypointCodec.Options.BARE_COORDINATES, true);

        assertTrue(message.getSiblings().stream()
                .anyMatch(component -> component.getContents() instanceof TranslatableContents translated
                        && translated.getKey().equals(
                        "waypointer.command.export.coordinates_only")));
        assertFalse(message.getSiblings().stream()
                .anyMatch(component -> component.getContents() instanceof TranslatableContents translated
                        && translated.getKey().equals(
                        "waypointer.command.export.without_names")));
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
        assertTrue(payload.startsWith("WP:"));
        assertFalse(payload.startsWith("WPL:"));
        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(payload);
        assertEquals(UniversalShareCodec.Type.WAYPOINTS, decoded.type());
        assertEquals(1, ((UniversalShareCodec.Waypoints) decoded).result()
                .libraryMetadata().folders().size());
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
    void routeImportOpenActionTargetsTheFirstImportedGroupWithSafeQuoting() {
        WaypointGroup first = new WaypointGroup("route:id with spaces", "First", "hub");
        first.add(Waypoint.at(1, 2, 3));
        WaypointGroup second = new WaypointGroup("second", "Second", "hub");
        second.add(Waypoint.at(4, 5, 6));
        String payload = UniversalShareCodec.encodeWaypoints(
                List.of(first, second), WaypointCodec.Options.FULL_FIDELITY,
                RouteLibraryMetadata.empty());
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerShareCommands commands = commands(manager, new CapturingScheduler());
        CommandMessages messages = new CommandMessages();

        commands.finishImport(messages.source(), WaypointCommandImport.decode(payload),
                "argument", Zone.fromId("hub"));

        Component success = messages.feedback.getLast();
        Component open = success.getSiblings().getLast();
        ClickEvent.RunCommand runCommand = assertInstanceOf(
                ClickEvent.RunCommand.class, open.getStyle().getClickEvent());
        assertEquals("/waypointer gui " + manager.allGroupsList().getFirst().id(),
                runCommand.command());
        assertEquals(List.of("First", "Second"),
                manager.allGroupsList().stream().map(WaypointGroup::name).toList());

        Component quotedOpen = WaypointerShareCommands.importEditorOpenComponent(
                false, "route:id with spaces");
        ClickEvent.RunCommand quotedCommand = assertInstanceOf(
                ClickEvent.RunCommand.class, quotedOpen.getStyle().getClickEvent());
        assertEquals("/waypointer gui \"route:id with spaces\"", quotedCommand.command());
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

    private static final class CapturingConfirmation
            implements WaypointerShareCommands.ConfigImportConfirmationPresenter {
        private int calls;
        private WaypointerConfig current;
        private WaypointerConfig imported;
        private Consumer<ConfigImportConfirmation.Outcome> completion;

        @Override
        public void present(WaypointerConfig current, WaypointerConfig imported,
                            Consumer<ConfigImportConfirmation.Outcome> completion) {
            calls++;
            this.current = current;
            this.imported = imported;
            this.completion = completion;
        }

        private void resolve(boolean confirmed) {
            Consumer<ConfigImportConfirmation.Outcome> pending = completion;
            completion = null;
            pending.accept(ConfigImportConfirmation.complete(current, imported, confirmed));
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

        private List<String> errorKeys() {
            return errors.stream()
                    .map(WaypointerShareCommandsTest::translationKey)
                    .toList();
        }
    }
}
