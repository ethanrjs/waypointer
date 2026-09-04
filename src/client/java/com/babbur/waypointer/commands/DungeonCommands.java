package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.screen.CodecWorker;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;


public final class DungeonCommands {

    private static final int IMPORT_FILE_MAX_BYTES = 8 * 1024 * 1024;

    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final ActiveGroupManager manager;

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           ActiveGroupManager manager) {
        this.tracker = tracker;
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> register(dispatcher));
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal("wpd")
                .executes(ctx -> runInfo(ctx.getSource()))
                .then(literal("info").executes(ctx -> runInfo(ctx.getSource())))
                .then(literal("room")
                        .then(literal("list").executes(ctx -> runRoomList(ctx.getSource()))))
                .then(literal("import")
                        .then(argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> runImport(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file")))))
                .then(literal("route")
                        .then(literal("next").executes(ctx -> runRouteNext(ctx.getSource())))
                        .then(literal("reset").executes(ctx -> runRouteReset(ctx.getSource())))
                        .then(literal("found")
                                .then(argument("secretIndex", IntegerArgumentType.integer(1))
                                        .suggests(suggestSecretIndices())
                                        .executes(ctx -> runRouteFound(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "secretIndex"))))))
                .then(literal("rotate")
                        .then(literal("nw").executes(ctx -> runRotate(ctx.getSource(), Direction.NW)))
                        .then(literal("ne").executes(ctx -> runRotate(ctx.getSource(), Direction.NE)))
                        .then(literal("sw").executes(ctx -> runRotate(ctx.getSource(), Direction.SW)))
                        .then(literal("se").executes(ctx -> runRotate(ctx.getSource(), Direction.SE)))
                        .then(literal("auto").executes(ctx -> runRotate(ctx.getSource(), null))))
                .then(literal("toggle")
                        .then(literal("enabled").executes(ctx -> runToggle(ctx.getSource(), "enabled")))
                        .then(literal("debug").executes(ctx -> runToggle(ctx.getSource(), "debug")))
                        .then(literal("hidecompleted").executes(ctx -> runToggle(ctx.getSource(), "hidecompleted"))));
        LiteralCommandNode<FabricClientCommandSource> canonical = d.register(cmd);
        d.register(literal("waypointer-dungeon").redirect(canonical));
    }


    private SuggestionProvider<FabricClientCommandSource> suggestSecretIndices() {
        return (ctx, builder) -> {
            WaypointGroup route = currentRoute();
            if (route == null) return builder.buildFuture();
            String prefix = builder.getRemaining();
            int ordinal = 0;
            for (int i = 0; i < route.size(); i++) {
                if (route.isSubwaypoint(i)) continue;
                ordinal++;
                String value = Integer.toString(ordinal);
                if (value.startsWith(prefix)) {
                    builder.suggest(ordinal,
                            Component.translatable(
                                    "waypointer.dungeon.command.suggestion.secret",
                                    ordinal));
                }
            }
            return builder.buildFuture();
        };
    }

    private WaypointGroup currentRoute() {
        DungeonRoom room = tracker.currentRoom();
        if (manager == null || room == null || !room.hasRoomId()) return null;
        return DungeonRoomRouteLibrary.storedRouteForRoom(manager, room.roomId());
    }

    private int runInfo(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (!tracker.inDungeon()) {
            info(src, Component.translatable("waypointer.dungeon.command.info.not_in_catacombs"));
            return 1;
        }
        if (room == null) {
            info(src, Component.translatable("waypointer.dungeon.command.info.no_room"));
            return 1;
        }
        info(src, Component.translatable(
                "waypointer.dungeon.command.info.room",
                room.displayName(), room.type(), room.shape(), room.direction(),
                room.hasRoomId() ? room.roomId() : "<unmatched>",
                room.physicalCornerX(), room.physicalCornerZ(),
                room.segments().size(), roomCountsSuffix(room)));
        return 1;
    }

    private static String roomCountsSuffix(DungeonRoom room) {
        if (!room.hasRoomId()) return "";
        DungeonRoomCatalogEntry definition = DungeonRoomData.entry(room.roomId());
        if (definition == null) return "";
        StringBuilder counts = new StringBuilder();
        if (definition.hasSecretCount()) counts.append(" secrets=").append(definition.secretCount());
        if (definition.hasCryptCount()) counts.append(" crypts=").append(definition.cryptCount());
        if (definition.hasTrappedChestCount()) {
            counts.append(" trappedChests=").append(definition.trappedChestCount());
        }
        return counts.toString();
    }

    private int runRoomList(FabricClientCommandSource src) {
        int count = 0;
        if (manager == null) return 0;
        for (WaypointGroup route : manager.allGroups()) {
            if (route.temp() || route.runtimeOnly()
                    || route.routeKind() != WaypointGroup.RouteKind.DUNGEON) continue;
            info(src, Component.translatable(
                    "waypointer.dungeon.command.room.list_entry",
                    route.zoneId(), route.name(), "ROUTE", "RELATIVE",
                    route.size(), 0));
            count++;
        }
        if (count == 0) {
            info(src, Component.translatable("waypointer.dungeon.command.room.list_empty"));
        }
        return count;
    }

    private int runImport(FabricClientCommandSource src, String rawPath) {
        Path file;
        try {
            file = resolveImportPath(rawPath);
        } catch (InvalidPathException invalidPath) {
            error(src, Component.translatable(
                    "waypointer.command.import.failed", invalidPath.getReason()));
            return 0;
        }
        if (file == null) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.import.file_not_found", rawPath));
            return 0;
        }

        if (!CodecWorker.run(() -> readImportFile(file, IMPORT_FILE_MAX_BYTES),
                loaded -> finishImport(src, file, loaded))) {
            error(src, Component.translatable("waypointer.codec.busy"));
            return 0;
        }
        return 1;
    }

    static ImportReadResult readImportFile(Path file, int maxBytes) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("route data is too large (max " + maxBytes + " bytes)");
            }
            String payload = new String(bytes, StandardCharsets.UTF_8);
            return new ImportReadResult(decodeImportPayload(payload), null, false);
        } catch (IOException readFailure) {
            return new ImportReadResult(null, errorMessage(readFailure, "could not read route data"), true);
        } catch (IllegalArgumentException invalidPayload) {
            return new ImportReadResult(null, errorMessage(invalidPayload, "invalid route data"), false);
        }
    }

    static DungeonRouteImporter.Result decodeImportPayload(String payload) {
        IllegalArgumentException legacyFailure;
        try {
            // Preserve the established WPD/JSON/Odin/SecretRoutes precedence and
            // reporting. Universal decoding is the typed fallback for new WP shares.
            return DungeonRouteImporter.parse(payload);
        } catch (IllegalArgumentException failure) {
            legacyFailure = failure;
        }

        UniversalShareCodec.Decoded decoded;
        try {
            decoded = UniversalShareCodec.decode(payload);
        } catch (IllegalArgumentException universalFailure) {
            if (looksLikeTypedWaypointerShare(payload)) throw universalFailure;
            throw legacyFailure;
        }
        if (decoded instanceof UniversalShareCodec.DungeonRoutes dungeonRoutes) {
            return dungeonRoutes.result();
        }
        if (decoded instanceof UniversalShareCodec.Configuration) {
            throw new IllegalArgumentException(
                    "expected a dungeon route share, got a configuration share");
        }
        if (decoded instanceof UniversalShareCodec.CatalogReference) {
            throw new IllegalArgumentException(
                    "expected a dungeon route share, got a catalog route link; use /wp import");
        }
        throw new IllegalArgumentException(
                "expected a dungeon route share, got a waypoint route share");
    }

    private static boolean looksLikeTypedWaypointerShare(String payload) {
        if (payload == null) return false;
        String text = payload.trim();
        if (text.startsWith("```") && text.endsWith("```") && text.length() >= 6) {
            int bodyStart = 3;
            int newline = text.indexOf('\n', bodyStart);
            if (newline >= 0) bodyStart = newline + 1;
            String body = text.substring(bodyStart, text.length() - 3).strip();
            if (!body.isEmpty()) text = body;
        }
        return text.startsWith("WP:") || text.startsWith("WPC:");
    }

    private static String errorMessage(Exception failure, String fallback) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private void finishImport(FabricClientCommandSource src, Path file, ImportReadResult loaded) {
        if (loaded == null) {
            error(src, Component.translatable(
                    "waypointer.command.import.failed", "unexpected import failure"));
            return;
        }
        if (loaded.error() != null) {
            error(src, loaded.readFailure()
                    ? Component.translatable(
                            "waypointer.dungeon.command.import.read_failed", file, loaded.error())
                    : Component.translatable("waypointer.command.import.failed", loaded.error()));
            return;
        }

        DungeonRouteImporter.Result result = loaded.result();
        List<WaypointGroup> routes = DungeonRoomRouteLibrary.installRoutes(manager, result.groups());
        reportImportedRoutes(src, result, routes);
    }

    static void reportImportedRoutes(FabricClientCommandSource src,
                                     DungeonRouteImporter.Result result,
                                     List<WaypointGroup> routes) {
        if (routes.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.import.no_usable_routes"));
        } else {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.import.success",
                    DungeonRoomShareCodec.waypointCount(routes), routes.size(),
                    importFormatLabel(result.format())));
            info(src, Component.translatable(
                    "waypointer.dungeon.routes.existing_disabled"));
        }
        if (result.skippedVariants() > 0) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.import.skipped_variants",
                    result.skippedVariants()));
        }
        if (!result.unmatchedRooms().isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.import.unmatched_rooms",
                    result.unmatchedRooms().size(),
                    summarizeNames(result.unmatchedRooms())));
        }
    }

    record ImportReadResult(DungeonRouteImporter.Result result,
                            String error, boolean readFailure) {}

    private static Path resolveImportPath(String rawPath) {
        String expanded = rawPath.trim();
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path direct = Path.of(expanded);
        if (Files.isRegularFile(direct)) return direct;
        if (!direct.isAbsolute()) {
            Path inGameDir = Minecraft.getInstance().gameDirectory.toPath().resolve(expanded);
            if (Files.isRegularFile(inGameDir)) return inGameDir;
            Path inConfigDir = FabricLoader.getInstance().getConfigDir().resolve(expanded);
            if (Files.isRegularFile(inConfigDir)) return inConfigDir;
        }
        return null;
    }

    private static Component importFormatLabel(DungeonRouteImporter.Format format) {
        return switch (format) {
            case WAYPOINTER -> Component.translatable(
                    "waypointer.dungeon.command.import.format.waypointer");
            case SECRET_ROUTES -> Component.translatable(
                    "waypointer.dungeon.command.import.format.secret_routes");
            case ODIN_PACK -> Component.translatable(
                    "waypointer.dungeon.command.import.format.odin");
        };
    }

    private static String summarizeNames(List<String> names) {
        int shown = Math.min(names.size(), 8);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            out.append(names.get(i));
        }
        if (names.size() > shown) out.append(", +").append(names.size() - shown).append(" more");
        return out.toString();
    }

    private int runRouteNext(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        WaypointGroup route = currentRoute();
        if (route == null) return 0;
        route.advancePast(route.currentIndex());
        manager.fireDataChanged();
        if (route.isComplete()) {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.route.completed", room.displayName()));
        } else {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.route.advanced", route.currentMainOrdinal()));
        }
        return 1;
    }

    private int runRouteReset(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        WaypointGroup route = currentRoute();
        if (route == null) return 0;
        route.resetProgress();
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.dungeon.command.route.reset", room.displayName()));
        return 1;
    }

    private int runRouteFound(FabricClientCommandSource src, int secretIndex) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        WaypointGroup route = currentRoute();
        int waypointIndex = mainIndexForOrdinal(route, secretIndex);
        if (waypointIndex < 0) {
            String available = availableAuthoredSecretIndexes(route);
            error(src, available.isEmpty()
                    ? Component.translatable(
                            "waypointer.dungeon.command.route.secret_not_authored_empty",
                            secretIndex, room.displayName())
                    : Component.translatable(
                            "waypointer.dungeon.command.route.secret_not_authored",
                            secretIndex, room.displayName(), available));
            return 0;
        }
        route.setCurrentTargetIndex(waypointIndex);
        route.advancePast(waypointIndex);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.dungeon.command.route.secret_found", secretIndex));
        return 1;
    }

    static boolean isAuthoredSecretIndex(WaypointGroup route, int secretIndex) {
        return mainIndexForOrdinal(route, secretIndex) >= 0;
    }

    static String availableAuthoredSecretIndexes(WaypointGroup route) {
        StringBuilder available = new StringBuilder();
        int count = route == null ? 0 : route.mainWaypointCount();
        for (int index = 1; index <= count; index++) {
            if (!available.isEmpty()) available.append(", ");
            available.append("#").append(index);
        }
        return available.toString();
    }

    private static int mainIndexForOrdinal(WaypointGroup route, int ordinal) {
        if (route == null || ordinal <= 0) return -1;
        int seen = 0;
        for (int i = 0; i < route.size(); i++) {
            if (route.isSubwaypoint(i)) continue;
            if (++seen == ordinal) return i;
        }
        return -1;
    }

    private int runRotate(FabricClientCommandSource src, Direction dir) {
        if (!tracker.setDirectionOverride(dir)) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.no_room"));
            return 0;
        }
        if (dir == null) {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.automatic"));
        } else {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.set", dir));
        }
        return 1;
    }

    private int runToggle(FabricClientCommandSource src, String which) {
        boolean newValue = switch (which) {
            case "enabled" -> {
                boolean v = !config.enabled();
                config.setEnabled(v);
                yield v;
            }
            case "debug" -> {
                boolean v = !config.debugLogRoomChanges();
                config.setDebugLogRoomChanges(v);
                yield v;
            }
            case "hidecompleted" -> {
                boolean v = !config.hideCompletedRooms();
                config.setHideCompletedRooms(v);
                yield v;
            }
            default -> false;
        };
        success(src, Component.translatable(
                "waypointer.dungeon.command.toggle", which, newValue));
        Waypointer.LOGGER.info("Dungeon toggle: {} -> {}", which, newValue);
        return 1;
    }

    private DungeonRoom requireRoom(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            error(src, Component.translatable("waypointer.dungeon.command.error.no_room"));
        }
        return room;
    }


    private static void info(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.GRAY)));
    }

    private static void success(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.GREEN)));
    }

    private static void error(FabricClientCommandSource src, Component msg) {
        src.sendError(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.RED)));
    }
}
