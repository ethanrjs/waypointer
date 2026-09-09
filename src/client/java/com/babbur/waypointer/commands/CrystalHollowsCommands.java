package com.babbur.waypointer.commands;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser;
import com.babbur.waypointer.crystal.CrystalHollowsGeometry;
import com.babbur.waypointer.crystal.CrystalHollowsLobbyState;
import com.babbur.waypointer.crystal.CrystalHollowsSightingSelector;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.CrystalHollowsTracker;
import com.babbur.waypointer.crystal.SightingConfidence;
import com.babbur.waypointer.crystal.StructureSighting;
import com.babbur.waypointer.crystal.WishingCompassController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static com.babbur.waypointer.commands.CommandHelpers.error;
import static com.babbur.waypointer.commands.CommandHelpers.success;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class CrystalHollowsCommands {

    private final CrystalHollowsTracker tracker;

    public CrystalHollowsCommands(CrystalHollowsTracker tracker,
                                  WishingCompassController compass,
                                  WaypointerConfig config) {
        this.tracker = tracker;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> register(dispatcher));
    }

    LiteralCommandNode<FabricClientCommandSource> register(
            CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> command = literal("wpch")
                .then(literal("share")
                        .then(argument("structure", StringArgumentType.word())
                                .then(argument("x", IntegerArgumentType.integer(
                                        CrystalHollowsGeometry.MIN_XZ, CrystalHollowsGeometry.MAX_XZ - 1))
                                        .then(argument("y", IntegerArgumentType.integer(30, 188))
                                                .then(argument("z", IntegerArgumentType.integer(
                                                        CrystalHollowsGeometry.MIN_XZ, CrystalHollowsGeometry.MAX_XZ - 1))
                                                        .executes(context -> runShare(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "structure"),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"))))))))
                .then(literal("add")
                        .then(argument("structure", StringArgumentType.word())
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> runAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "structure"),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"))))))));
        return dispatcher.register(command);
    }

    private int runShare(FabricClientCommandSource source, String rawStructure, int x, int y, int z) {
        if (availableLobby(source) == null) return 0;
        CrystalHollowsStructure structure = resolveStructure(rawStructure);
        if (structure == null) return unknownStructure(source, rawStructure);
        var connection = source.getClient().getConnection();
        if (connection == null) return 0;
        connection.sendChat(CrystalHollowsChatParser.formatShare(structure, x, y, z));
        return 1;
    }

    private int runAdd(FabricClientCommandSource source, String rawStructure, int x, int y, int z) {
        if (availableLobby(source) == null) return 0;
        CrystalHollowsStructure structure = resolveStructure(rawStructure);
        if (structure == null) return unknownStructure(source, rawStructure);
        tracker.merge(new StructureSighting(structure, x, y, z, SightingConfidence.MANUAL,
                "manual:player", System.currentTimeMillis()), false);
        success(source, Component.translatable("waypointer.crystal.command.added",
                displayName(structure), x, y, z));
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
        return CrystalHollowsSightingSelector.resolveStructure(raw);
    }

    private static Component displayName(CrystalHollowsStructure structure) {
        return Component.translatable("waypointer.crystal.structure." + structure.id());
    }

}
