package com.babbur.waypointer.commands;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.codec.RouteLibraryMetadata;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.color.RouteColorPolicy;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.debug.ConfigChangeHistory;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.screen.CatalogRouteInstallScreen;
import com.babbur.waypointer.screen.CodecWorker;
import com.babbur.waypointer.screen.ConfigImportConfirmation;
import com.babbur.waypointer.screen.ImportFeedback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerShareCommands {

    private static final int IMPORT_FILE_MAX_BYTES = 8 * 1024 * 1024;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final ChatImportCache chatImportCache;
    private final RouteExportScheduler routeExportScheduler;
    private final ConfigImportConfirmationPresenter configImportConfirmationPresenter;

    WaypointerShareCommands(ActiveGroupManager manager, WaypointerConfig config,
                            ChatImportCache chatImportCache) {
        this(manager, config, chatImportCache, WaypointerShareCommands::scheduleRouteExport,
                WaypointerShareCommands::showConfigImportConfirmation);
    }

    WaypointerShareCommands(ActiveGroupManager manager, WaypointerConfig config,
                            ChatImportCache chatImportCache,
                            RouteExportScheduler routeExportScheduler) {
        this(manager, config, chatImportCache, routeExportScheduler,
                WaypointerShareCommands::showConfigImportConfirmation);
    }

    WaypointerShareCommands(ActiveGroupManager manager, WaypointerConfig config,
                            ChatImportCache chatImportCache,
                            RouteExportScheduler routeExportScheduler,
                            ConfigImportConfirmationPresenter configImportConfirmationPresenter) {
        this.manager = manager;
        this.config = config;
        this.chatImportCache = chatImportCache;
        this.routeExportScheduler = Objects.requireNonNull(
                routeExportScheduler, "routeExportScheduler");
        this.configImportConfirmationPresenter = Objects.requireNonNull(
                configImportConfirmationPresenter, "configImportConfirmationPresenter");
    }

    int runExport(FabricClientCommandSource src, WaypointCodec.Options opts) {
        List<WaypointGroup> toExport = cliExportGroups(manager);
        if (toExport.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.export.no_active_route"));
            return 0;
        }
        return scheduleExport(src, toExport, opts);
    }

    int runExportRoutes(FabricClientCommandSource src, WaypointCodec.Options opts) {
        List<WaypointGroup> toExport = cliBulkExportGroups(manager);
        if (toExport.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.export.no_bulk_routes"));
            return 0;
        }
        return scheduleExport(src, toExport, opts);
    }

    private int scheduleExport(FabricClientCommandSource src,
                               List<WaypointGroup> toExport,
                               WaypointCodec.Options opts) {
        WaypointCodec.Options effectiveOptions = productOptionsForGroups(toExport, opts);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(manager, toExport);
        List<WaypointGroup> snapshot = toExport.stream()
                .map(WaypointGroup::exportSnapshot)
                .toList();
        info(src, Component.translatable("waypointer.command.export.exporting"));
        if (!routeExportScheduler.schedule(snapshot, effectiveOptions, metadata,
                payload -> finishExport(src, snapshot.size(), payload, effectiveOptions))) {
            codecBusy(src);
            return 0;
        }
        return snapshot.size();
    }

    private static WaypointCodec.Options productOptionsForGroups(
            List<WaypointGroup> groups, WaypointCodec.Options options) {
        if (options.isBareCoordinateProjection()
                || options.includeNames
                || options.includeColors
                || options.includeRadii
                || options.includeWaypointFlags
                || options.includeGroupMeta
                || options.includeZone
                || !options.label.isEmpty()
                || groups == null
                || groups.isEmpty()
                || groups.stream().anyMatch(group -> group == null
                        || group.routeKind() != WaypointGroup.RouteKind.REGULAR)) {
            return options;
        }
        return WaypointCodec.Options.BARE_COORDINATES;
    }

    private static boolean scheduleRouteExport(
            List<WaypointGroup> groups,
            WaypointCodec.Options options,
            RouteLibraryMetadata metadata,
            Consumer<String> completion) {
        return CodecWorker.run(
                () -> UniversalShareCodec.encodeWaypoints(groups, options, metadata), completion);
    }

    static void finishExport(FabricClientCommandSource src, int routeCount,
                                     String payload, WaypointCodec.Options options) {
        if (payload == null) {
            error(src, Component.translatable(
                    "waypointer.command.export.failed", "unexpected encoding failure"));
            return;
        }
        boolean copied = setClipboard(payload);
        MutableComponent line = exportSuccessMessage(routeCount, payload, options, copied);
        src.sendFeedback(WaypointerChatFeedback.suppress(line));
    }

    int runExportConfig(FabricClientCommandSource src) {
        WaypointerConfig snapshot = new WaypointerConfig();
        snapshot.replaceShareableSettingsWith(config);
        if (!CodecWorker.run(() -> UniversalShareCodec.encodeConfig(snapshot),
                payload -> finishConfigExport(src, payload))) {
            codecBusy(src);
            return 0;
        }
        return 1;
    }

    static void finishConfigExport(FabricClientCommandSource src, String payload) {
        if (payload == null) {
            error(src, Component.translatable(
                    "waypointer.command.export.failed", "unexpected encoding failure"));
            return;
        }
        boolean copied = setClipboard(payload);
        MutableComponent line = Component.literal("Exported config code (" + payload.length() + " characters).")
                .withStyle(ChatFormatting.GREEN);
        if (copied) line.append(Component.literal(" Copied to clipboard.").withStyle(ChatFormatting.GRAY));
        if (!copied) {
            line.append(Component.literal(" Copy")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.CopyToClipboard(payload))));
        }
        info(src, line);
    }

    int runExportDungeon(FabricClientCommandSource src) {
        List<WaypointGroup> routes = manager.allGroups().stream()
                .filter(group -> group != null && !group.temp() && !group.runtimeOnly()
                        && group.routeKind() == WaypointGroup.RouteKind.DUNGEON)
                .sorted(Comparator.comparing((WaypointGroup group) ->
                        group.name().toLowerCase(Locale.ROOT)).thenComparing(WaypointGroup::id))
                .toList();
        if (routes.isEmpty()) {
            info(src, Component.literal("No dungeon routes are available to export."));
            return 0;
        }

        int waypoints = com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec
                .waypointCount(routes);
        if (!CodecWorker.run(() -> UniversalShareCodec.encodeDungeon(routes),
                payload -> finishDungeonExport(src, routes.size(), waypoints, payload))) {
            codecBusy(src);
            return 0;
        }
        return routes.size();
    }

    static void finishDungeonExport(FabricClientCommandSource src, int definitionCount,
                                            int waypoints, String payload) {
        if (payload == null) {
            error(src, Component.translatable(
                    "waypointer.command.export.failed", "unexpected encoding failure"));
            return;
        }
        boolean copied = setClipboard(payload);
        MutableComponent line = Component.literal("Exported " + definitionCount + " dungeon room route"
                        + (definitionCount == 1 ? "" : "s") + " (" + waypoints + " waypoint"
                        + (waypoints == 1 ? "" : "s") + ", " + payload.length() + " characters).")
                .withStyle(ChatFormatting.GREEN);
        if (copied) line.append(Component.literal(" Copied to clipboard.").withStyle(ChatFormatting.GRAY));
        if (!copied) {
            line.append(Component.literal(" Copy")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.CopyToClipboard(payload))));
        }
        info(src, line);
    }

    static MutableComponent exportSuccessMessage(int routeCount, String payload,
                                                 WaypointCodec.Options options, boolean copied) {
        MutableComponent line = Component.translatable(
                        "waypointer.command.export.success",
                        routeCount, payload.length())
                .withStyle(ChatFormatting.GREEN);
        if (options.isBareCoordinateProjection()) {
            line.append(Component.translatable(
                    "waypointer.command.export.coordinates_only")
                    .withStyle(ChatFormatting.GRAY));
        } else if (!options.includeNames) {
            line.append(Component.translatable(
                    "waypointer.command.export.without_names")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (copied) {
            line.append(Component.translatable(
                    "waypointer.command.export.copied")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            line.append(Component.translatable("waypointer.command.export.copy")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.CopyToClipboard(payload))));
        }
        return line;
    }

    static List<WaypointGroup> cliExportGroups(ActiveGroupManager manager) {
        if (manager == null) return List.of();
        for (WaypointGroup visible : manager.activeGroups()) {
            if (visible == null || visible.isEmpty() || visible.temp()) continue;
            if (!visible.runtimeOnly()
                    && visible.routeKind() == WaypointGroup.RouteKind.REGULAR) {
                return List.of(visible);
            }
            if (!visible.runtimeOnly()
                    || visible.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
                continue;
            }
            WaypointGroup durable = DungeonRoomRouteLibrary.durableEditTarget(manager, visible);
            if (isSafeDurableDungeonTarget(manager, visible, durable)) {
                return List.of(durable);
            }
        }
        return List.of();
    }

    private static boolean isSafeDurableDungeonTarget(
            ActiveGroupManager manager,
            WaypointGroup visible,
            WaypointGroup durable) {
        return durable != null
                && manager.get(durable.id()) == durable
                && !durable.temp()
                && !durable.runtimeOnly()
                && !durable.isEmpty()
                && durable.routeKind() == WaypointGroup.RouteKind.DUNGEON
                && durable.id().equals(visible.runtimeSourceGroupId())
                && durable.zoneId().equals(visible.zoneId());
    }

    static List<WaypointGroup> cliBulkExportGroups(ActiveGroupManager manager) {
        if (manager == null) return List.of();
        return manager.allGroups().stream()
                .filter(group -> group != null
                        && !group.temp()
                        && !group.runtimeOnly()
                        && group.routeKind() == WaypointGroup.RouteKind.REGULAR)
                .toList();
    }

    @FunctionalInterface
    interface RouteExportScheduler {
        boolean schedule(List<WaypointGroup> groups,
                         WaypointCodec.Options options,
                         RouteLibraryMetadata metadata,
                         Consumer<String> completion);
    }

    @FunctionalInterface
    interface ConfigImportConfirmationPresenter {
        void present(WaypointerConfig current, WaypointerConfig imported,
                     Consumer<ConfigImportConfirmation.Outcome> completion);
    }

    @FunctionalInterface
    interface CatalogReferencePresenter {
        void present(String routeId);
    }

    private CatalogReferencePresenter catalogReferencePresenter =
            WaypointerShareCommands::openCatalogReference;

    void setCatalogReferencePresenter(CatalogReferencePresenter presenter) {
        this.catalogReferencePresenter = Objects.requireNonNull(presenter, "presenter");
    }

    private static void openCatalogReference(String routeId) {
        Minecraft minecraft = Minecraft.getInstance();
        CatalogRouteInstallScreen.open(MinecraftCompat.screen(minecraft), routeId);
    }

    int runImportFromClipboard(FabricClientCommandSource src) {
        String text = getClipboard();
        if (text == null || text.isBlank()) {
            error(src, Component.translatable(
                    "waypointer.command.import.clipboard_empty",
                    WaypointCodec.MAGIC));
            return 0;
        }
        return scheduleImport(src, text, "clipboard");
    }

    int runImportFile(FabricClientCommandSource src, String rawPath) {
        String cleaned = stripQuotes(rawPath).trim();
        if (cleaned.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.command.import.file_usage"));
            return 0;
        }

        Path path;
        try {
            path = Path.of(cleaned);
        } catch (InvalidPathException e) {
            error(src, Component.translatable(
                    "waypointer.command.import.invalid_path", e.getReason()));
            return 0;
        }
        String origin = "file:" + path.getFileName();
        Zone targetZone = manager.currentZone();
        if (!CodecWorker.run(() -> WaypointCommandImport.readAndDecode(
                        path, IMPORT_FILE_MAX_BYTES),
                result -> finishImport(src, result, origin, targetZone))) {
            codecBusy(src);
            return 0;
        }
        return 1;
    }

    static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    int runImportChat(FabricClientCommandSource src, String handle) {
        return runImportChatTyped(src, handle, null);
    }

    int runImportChatTyped(FabricClientCommandSource src, String handle,
                           UniversalShareCodec.Type expectedType) {
        String codec = chatImportCache.get(handle);
        if (codec == null) {
            error(src, Component.translatable(
                    "waypointer.command.import.expired"));
            return 0;
        }
        return scheduleImport(src, codec, "chat", expectedType);
    }

    int runImportArgument(FabricClientCommandSource src, String payload) {
        String cached = chatImportCache.get(payload);
        if (cached != null) {
            return scheduleImport(src, cached, "chat");
        }
        return scheduleImport(src, payload, "argument");
    }

    int scheduleImport(FabricClientCommandSource src, String payload, String origin) {
        return scheduleImport(src, payload, origin, null);
    }

    int scheduleImport(FabricClientCommandSource src, String payload, String origin,
                       UniversalShareCodec.Type expectedType) {
        Zone targetZone = manager.currentZone();
        if (!CodecWorker.run(() -> WaypointCommandImport.decode(payload),
                result -> finishImport(src, result, origin, targetZone, expectedType))) {
            codecBusy(src);
            return 0;
        }
        return 1;
    }

    static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "invalid import data" : message;
    }

    void finishImport(FabricClientCommandSource src, WaypointCommandImport.Result result,
                              String origin,
                              Zone targetZone) {
        finishImport(src, result, origin, targetZone, null);
    }

    void finishImport(FabricClientCommandSource src, WaypointCommandImport.Result result,
                      String origin, Zone targetZone,
                      UniversalShareCodec.Type expectedType) {
        if (result == null) {
            error(src, Component.translatable(
                    "waypointer.command.import.failed", "unexpected import failure"));
            ImportFeedback.failure("Invalid import text.");
            return;
        }
        if (result.error() != null) {
            error(src, result.error());
            ImportFeedback.failure("Invalid import text.");
            return;
        }

        UniversalShareCodec.Decoded decoded = result.decoded();
        if (expectedType != null && decoded.type() != expectedType) {
            error(src, Component.translatable("waypointer.command.import.wrong_type",
                    expectedType.name().toLowerCase(Locale.ROOT),
                    decoded.type().name().toLowerCase(Locale.ROOT)));
            return;
        }
        if (decoded instanceof UniversalShareCodec.CatalogReference reference) {
            // Nothing is installed yet: the preview screen fetches the route
            // from the catalog and asks before adding it.
            info(src, Component.translatable("waypointer.command.import.catalog.opening",
                    reference.routeId()));
            try {
                catalogReferencePresenter.present(reference.routeId());
            } catch (RuntimeException failure) {
                error(src, Component.translatable(
                        "waypointer.command.import.catalog.open_failed"));
            }
            return;
        }
        if (decoded instanceof UniversalShareCodec.Configuration configuration) {
            requestConfigImportConfirmation(src, configuration.config(), origin);
            return;
        }
        if (decoded instanceof UniversalShareCodec.DungeonRoutes dungeonRoutes) {
            importDungeonRoutes(src, dungeonRoutes.result(), origin);
            return;
        }

        WaypointImporter.ImportResult imported = ((UniversalShareCodec.Waypoints) decoded).result();
        int retargeted = retargetUnknownGroups(imported.groups(), targetZone);
        if (imported.libraryMetadata().isEmpty()) {
            RouteColorPolicy.applyImportedRouteDefaults(imported.groups(), config);
        }

        manager.addAll(imported.groups());
        imported.libraryMetadata().installFolders(manager, imported.groups());
        success(src, importSuccessMessage(
                imported.groups().size(), origin, imported.source(),
                retargeted, targetZone, imported.label()));

        ImportFeedback.success(imported.groups(), origin);
    }

    private void requestConfigImportConfirmation(FabricClientCommandSource src,
                                                  WaypointerConfig imported,
                                                  String origin) {
        info(src, Component.translatable("waypointer.command.import.config.review", origin));
        try {
            configImportConfirmationPresenter.present(config, imported, outcome -> {
                if (!outcome.confirmed()) {
                    info(src, Component.translatable(
                            "waypointer.command.import.config.cancelled", origin));
                    return;
                }
                ConfigChangeHistory.recordBulk("Imported config code ("
                        + outcome.changedSettings() + " changed)");
                success(src, Component.translatable(outcome.changedSettings() == 1
                                ? "waypointer.command.import.config.imported.one"
                                : "waypointer.command.import.config.imported.many",
                        origin, outcome.changedSettings()));
            });
        } catch (RuntimeException failure) {
            error(src, Component.translatable(
                    "waypointer.command.import.config.confirm_failed"));
        }
    }

    private static void showConfigImportConfirmation(
            WaypointerConfig current, WaypointerConfig imported,
            Consumer<ConfigImportConfirmation.Outcome> completion) {
        Minecraft minecraft = Minecraft.getInstance();
        ConfigImportConfirmation.open(MinecraftCompat.screen(minecraft),
                current, imported, completion);
    }

    static MutableComponent importSuccessMessage(int routeCount, String origin, Object source,
                                                 int retargeted, Zone targetZone, String label) {
        MutableComponent line = Component.translatable(
                        "waypointer.command.import.success", routeCount, origin, source)
                .withStyle(ChatFormatting.GREEN);
        if (retargeted > 0 && targetZone != null) {
            line.append(Component.literal(" \u00B7 ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(
                            retargeted == 1
                                    ? "waypointer.command.import.retargeted.one"
                                    : "waypointer.command.import.retargeted.many",
                            retargeted, targetZone.displayName())
                            .withStyle(ChatFormatting.GRAY));
        }
        if (label != null && !label.isEmpty()) {
            line.append(Component.literal(" \u00B7 ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(
                            "waypointer.command.import.label",
                            Component.literal(label).withStyle(ChatFormatting.WHITE))
                            .withStyle(ChatFormatting.GRAY));
        }
        if (routeCount > 0) {
            line.append(Component.literal(" \u00B7 ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(importEditorOpenComponent(false));
        }
        return line;
    }

    static void codecBusy(FabricClientCommandSource src) {
        error(src, Component.translatable("waypointer.codec.busy"));
    }

    int importDungeonRoutes(FabricClientCommandSource src,
                                    com.babbur.waypointer.dungeon.data.DungeonRouteImporter.Result result,
                                    String origin) {
        List<WaypointGroup> routes = DungeonRoomRouteLibrary.installRoutes(manager, result.groups());
        int imported = routes.size();
        int waypoints = com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec
                .waypointCount(routes);
        if (imported == 0) return 0;
        success(src, Component.literal("Imported " + imported + " dungeon room route"
                + (imported == 1 ? "" : "s") + " with " + waypoints + " waypoint"
                + (waypoints == 1 ? "" : "s") + " from " + origin + "."));
        info(src, importEditorHintComponent(true));
        return imported;
    }

    static Component importEditorHintComponent() {
        return importEditorHintComponent(false);
    }

    static Component importEditorHintComponent(boolean dungeonRoutes) {
        return Component.translatable("waypointer.command.import.open_editor_hint")
                .withStyle(ChatFormatting.GRAY)
                .append(importEditorOpenComponent(dungeonRoutes));
    }

    static Component importEditorOpenComponent(boolean dungeonRoutes) {
        String command = dungeonRoutes ? "/waypointer gui dungeon" : "/waypointer gui";
        return Component.translatable("waypointer.command.import.open_editor")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    static int retargetUnknownGroups(List<WaypointGroup> groups, Zone target) {
        if (target == null || target.id().equals(Zone.UNKNOWN.id())) return 0;
        int count = 0;
        for (WaypointGroup g : groups) {
            if (Zone.UNKNOWN.id().equals(g.zoneId())) {
                g.setZoneId(target.id());
                count++;
            }
        }
        return count;
    }

    WaypointCodec.Options exportOptionsFromConfig() {
        return WaypointCodec.Options.builder()
                .includeNames(config.exportIncludeNames())
                .includeColors(config.exportIncludeColors())
                .includeRadii(config.exportIncludeRadii())
                .includeWaypointFlags(config.exportIncludeWaypointFlags())
                .includeGroupMeta(config.exportIncludeGroupMeta())
                .includeZone(config.exportIncludeZone())
                .build();
    }

    static boolean setClipboard(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.keyboardHandler == null) return false;
            mc.keyboardHandler.setClipboard(text);
            return true;
        } catch (RuntimeException t) {
            Waypointer.LOGGER.warn("Clipboard write failed", t);
            return false;
        }
    }

    static String getClipboard() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                String text = mc.keyboardHandler.getClipboard();
                if (text != null && !text.isBlank()) return text;
            }
        } catch (RuntimeException t) {
            Waypointer.LOGGER.warn("Minecraft clipboard read failed", t);
        }
        return null;
    }
}
