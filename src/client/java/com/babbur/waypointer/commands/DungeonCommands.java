package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.screen.WaypointerScreen;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
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

    private final ActiveGroupManager manager;

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           ActiveGroupManager manager) {
        this.manager = manager;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> register(dispatcher));
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal("wpd")
                .executes(ctx -> openDungeonGui())
                .then(literal("import")
                        .then(argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> runImport(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file")))));
        LiteralCommandNode<FabricClientCommandSource> canonical = d.register(cmd);
        d.register(literal("waypointer-dungeon")
                .executes(ctx -> openDungeonGui())
                .redirect(canonical));
    }

    private int openDungeonGui() {
        Minecraft.getInstance().execute(
                () -> WaypointerScreen.openDungeonRooms(manager, WaypointerClient.config()));
        return 1;
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
                    "expected a dungeon route code, got a configuration code");
        }
        if (decoded instanceof UniversalShareCodec.CatalogReference) {
            throw new IllegalArgumentException(
                    "expected a dungeon route code, got a catalog route link; use /wp import");
        }
        throw new IllegalArgumentException(
                "expected a dungeon route code, got a waypoint route code");
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
