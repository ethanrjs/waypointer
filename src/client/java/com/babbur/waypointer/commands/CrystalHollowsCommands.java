package com.babbur.waypointer.commands;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser;
import com.babbur.waypointer.crystal.CrystalHollowsLobbyState;
import com.babbur.waypointer.crystal.CrystalHollowsStore;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.CrystalHollowsTracker;
import com.babbur.waypointer.crystal.SightingConfidence;
import com.babbur.waypointer.crystal.StructureSighting;
import com.babbur.waypointer.crystal.WishingCompassController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import static com.babbur.waypointer.commands.CommandHelpers.error;
import static com.babbur.waypointer.commands.CommandHelpers.info;
import static com.babbur.waypointer.commands.CommandHelpers.success;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client commands for the lobby-scoped Crystal Hollows feature. */
public final class CrystalHollowsCommands {

    private final CrystalHollowsTracker tracker;
    private final WishingCompassController compass;
    private final WaypointerConfig config;

    public CrystalHollowsCommands(CrystalHollowsTracker tracker,
                                  WishingCompassController compass,
                                  WaypointerConfig config) {
        this.tracker = tracker;
        this.compass = compass;
        this.config = config;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> register(dispatcher));
    }

    LiteralCommandNode<FabricClientCommandSource> register(
            CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> command = literal("wpch")
                .executes(context -> runInfo(context.getSource()))
                .then(literal("info").executes(context -> runInfo(context.getSource())))
                .then(literal("share")
                        .then(argument("structure", StringArgumentType.word())
                                .suggests(sightingSuggestions())
                                .executes(context -> runShare(context.getSource(),
                                        StringArgumentType.getString(context, "structure")))))
                .then(literal("add")
                        .then(argument("structure", StringArgumentType.word())
                                .executes(context -> runAddAtPlayer(context.getSource(),
                                        StringArgumentType.getString(context, "structure")))
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> runAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "structure"),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"))))))))
                .then(literal("remove")
                        .then(argument("structure", StringArgumentType.word())
                                .suggests(sightingSuggestions())
                                .executes(context -> runRemove(context.getSource(),
                                        StringArgumentType.getString(context, "structure")))))
                .then(literal("clear").executes(context -> runClear(context.getSource())))
                .then(literal("compass")
                        .executes(context -> runCompass(context.getSource()))
                        .then(literal("reset")
                                .executes(context -> runCompassReset(context.getSource()))))
                .then(literal("toggle")
                        .then(toggle("enabled"))
                        .then(toggle("compass"))
                        .then(toggle("chat"))
                        .then(toggle("entities"))
                        .then(toggle("rough")));
        LiteralCommandNode<FabricClientCommandSource> canonical = dispatcher.register(command);
        dispatcher.register(literal("waypointer-crystal").redirect(canonical));
        return canonical;
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> toggle(String option) {
        return literal(option).executes(context -> runToggle(context.getSource(), option));
    }

    private SuggestionProvider<FabricClientCommandSource> sightingSuggestions() {
        return (context, builder) -> {
            CrystalHollowsLobbyState lobby = tracker == null ? null : tracker.lobby();
            if (lobby == null) return builder.buildFuture();
            Set<String> ids = new LinkedHashSet<>();
            for (StructureSighting sighting : lobby.sightings()) ids.add(sighting.structure().id());
            String remaining = builder.getRemainingLowerCase();
            for (String id : ids) {
                if (id.startsWith(remaining)) builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private int runInfo(FabricClientCommandSource source) {
        CrystalHollowsLobbyState lobby = availableLobby(source);
        if (lobby == null) return 0;
        String day = lobby.lastKnownDay() < 0
                ? Component.translatable("waypointer.crystal.value.unknown").getString()
                : Integer.toString(lobby.lastKnownDay());
        String expiry = Instant.ofEpochMilli(CrystalHollowsStore.expiresAtMillis(lobby)).toString();
        info(source, Component.translatable("waypointer.crystal.command.info.summary",
                lobby.serverId(), day, expiry, lobby.sightings().size())
                .withStyle(ChatFormatting.AQUA));
        if (lobby.sightings().isEmpty()) {
            info(source, Component.translatable("waypointer.crystal.command.info.empty"));
            return 1;
        }
        double playerX = source.getPosition().x;
        double playerY = source.getPosition().y;
        double playerZ = source.getPosition().z;
        for (StructureSighting sighting : lobby.sightings()) {
            double dx = sighting.x() + 0.5 - playerX;
            double dy = sighting.y() + 0.5 - playerY;
            double dz = sighting.z() + 0.5 - playerZ;
            String distance = String.format(Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dy * dy + dz * dz));
            MutableComponent line = Component.translatable(
                            "waypointer.crystal.command.info.sighting",
                            displayName(sighting.structure()), sighting.x(), sighting.y(), sighting.z(),
                            confidence(sighting.confidence()), distance)
                    .append(Component.literal(" "))
                    .append(action("waypointer.crystal.action.share",
                            "/wpch share " + sighting.structure().id(), ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(action("waypointer.crystal.action.remove",
                            "/wpch remove " + sighting.structure().id(), ChatFormatting.RED));
            info(source, line);
        }
        return 1;
    }

    private int runShare(FabricClientCommandSource source, String rawStructure) {
        CrystalHollowsLobbyState lobby = availableLobby(source);
        if (lobby == null) return 0;
        CrystalHollowsStructure structure = resolveStructure(rawStructure);
        if (structure == null) return unknownStructure(source, rawStructure);
        StructureSighting sighting = lobby.sightings().stream()
                .filter(candidate -> candidate.structure() == structure)
                .findFirst().orElse(null);
        if (sighting == null) {
            error(source, Component.translatable(
                    "waypointer.crystal.command.error.not_found", displayName(structure)));
            return 0;
        }
        if (source.getClient().getConnection() == null) {
            error(source, Component.translatable("waypointer.crystal.command.error.no_connection"));
            return 0;
        }
        String message = CrystalHollowsChatParser.formatShare(
                structure, sighting.x(), sighting.y(), sighting.z());
        WaypointerChatFeedback.suppress(Component.literal(message));
        source.getClient().getConnection().sendChat(message);
        return 1;
    }

    private int runAddAtPlayer(FabricClientCommandSource source, String rawStructure) {
        return runAdd(source, rawStructure,
                (int) Math.floor(source.getPosition().x),
                (int) Math.floor(source.getPosition().y),
                (int) Math.floor(source.getPosition().z));
    }

    private int runAdd(FabricClientCommandSource source, String rawStructure, int x, int y, int z) {
        if (availableLobby(source) == null) return 0;
        CrystalHollowsStructure structure = resolveStructure(rawStructure);
        if (structure == null) return unknownStructure(source, rawStructure);
        tracker.merge(new StructureSighting(structure, x, y, z, SightingConfidence.MANUAL,
                "manual:player", System.currentTimeMillis()));
        success(source, Component.translatable("waypointer.crystal.command.added",
                displayName(structure), x, y, z));
        return 1;
    }

    private int runRemove(FabricClientCommandSource source, String rawStructure) {
        CrystalHollowsLobbyState lobby = availableLobby(source);
        if (lobby == null) return 0;
        CrystalHollowsStructure structure = resolveStructure(rawStructure);
        if (structure == null) return unknownStructure(source, rawStructure);
        if (lobby.sightings().stream().noneMatch(sighting -> sighting.structure() == structure)) {
            error(source, Component.translatable(
                    "waypointer.crystal.command.error.not_found", displayName(structure)));
            return 0;
        }
        tracker.remove(structure);
        success(source, Component.translatable(
                "waypointer.crystal.command.removed", displayName(structure)));
        return 1;
    }

    private int runClear(FabricClientCommandSource source) {
        if (availableLobby(source) == null) return 0;
        tracker.clearSightings();
        success(source, Component.translatable("waypointer.crystal.command.cleared"));
        return 1;
    }

    private int runCompass(FabricClientCommandSource source) {
        if (compass == null) {
            error(source, Component.translatable("waypointer.crystal.command.error.unavailable"));
            return 0;
        }
        info(source, Component.translatable("waypointer.crystal.command.compass.status",
                compass.solver().state().name().toLowerCase(Locale.ROOT),
                compass.solver().completedRays().size()));
        return 1;
    }

    private int runCompassReset(FabricClientCommandSource source) {
        if (compass == null) {
            error(source, Component.translatable("waypointer.crystal.command.error.unavailable"));
            return 0;
        }
        compass.reset();
        success(source, Component.translatable("waypointer.crystal.command.compass.reset"));
        return 1;
    }

    private int runToggle(FabricClientCommandSource source, String option) {
        boolean enabled;
        switch (option) {
            case "enabled" -> {
                enabled = !config.crystalHollowsEnabled();
                config.setCrystalHollowsEnabled(enabled);
            }
            case "compass" -> {
                enabled = !config.crystalHollowsWishingCompassSolver();
                config.setCrystalHollowsWishingCompassSolver(enabled);
                if (!enabled && compass != null) compass.reset();
            }
            case "chat" -> {
                enabled = !config.crystalHollowsChatDetection();
                config.setCrystalHollowsChatDetection(enabled);
            }
            case "entities" -> {
                enabled = !config.crystalHollowsEntityDetection();
                config.setCrystalHollowsEntityDetection(enabled);
            }
            case "rough" -> {
                enabled = !config.crystalHollowsShowRoughMarkers();
                config.setCrystalHollowsShowRoughMarkers(enabled);
            }
            default -> throw new IllegalArgumentException("unknown Crystal Hollows toggle: " + option);
        }
        if (tracker != null) tracker.configurationChanged();
        success(source, Component.translatable("waypointer.crystal.command.toggled",
                Component.translatable("waypointer.crystal.toggle." + option),
                Component.translatable(enabled
                        ? "waypointer.crystal.value.on"
                        : "waypointer.crystal.value.off")));
        return 1;
    }

    private CrystalHollowsLobbyState availableLobby(FabricClientCommandSource source) {
        CrystalHollowsLobbyState lobby = tracker == null ? null : tracker.lobby();
        if (tracker == null || !tracker.active() || lobby == null) {
            error(source, Component.translatable("waypointer.crystal.command.error.inactive"));
            return null;
        }
        return lobby;
    }

    private static int unknownStructure(FabricClientCommandSource source, String value) {
        error(source, Component.translatable(
                "waypointer.crystal.command.error.structure", value));
        return 0;
    }

    static CrystalHollowsStructure resolveStructure(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (structure.id().equalsIgnoreCase(raw)) return structure;
        }
        return CrystalHollowsChatParser.structureFromText(raw);
    }

    private static Component displayName(CrystalHollowsStructure structure) {
        return Component.translatable("waypointer.crystal.structure." + structure.id());
    }

    private static Component confidence(SightingConfidence confidence) {
        return Component.translatable("waypointer.crystal.confidence."
                + confidence.name().toLowerCase(Locale.ROOT));
    }

    private static Component action(String translationKey, String command, ChatFormatting color) {
        return Component.translatable(translationKey).withStyle(Style.EMPTY.withColor(color)
                .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand(command)));
    }
}
