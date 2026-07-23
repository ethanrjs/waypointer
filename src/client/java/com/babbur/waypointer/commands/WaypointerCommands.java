package com.babbur.waypointer.commands;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.color.RouteColorPolicy;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomRouteSync;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointSkipRules;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.screen.DebugInspectScreen;
import com.babbur.waypointer.screen.ImportFeedback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Registers {@code /waypointer} and short aliases as client-side commands.
 *
 * We lean on Brigadier's help-text for each subcommand's usage; the feedback messages
 * intentionally use the same vocabulary as the in-game UI ("route", "waypoint", "zone")
 * so the user doesn't have to translate concepts between CLI and GUI.
 *
 * Every state-mutating command ends by firing {@link ActiveGroupManager#fireDataChanged()}
 * so autosave and listeners react without the commands needing to know about them.
 */
public final class WaypointerCommands {

    private final ActiveGroupManager manager;
    private final Storage storage;
    private final WaypointerConfig config;
    private final ChatImportCache chatImportCache;
    private final Runnable openGui; // supplied by client init so we don't wire screens here
    private final WaypointAddFlow addFlow;

    public WaypointerCommands(ActiveGroupManager manager, Storage storage,
                              WaypointerConfig config, ChatImportCache chatImportCache,
                              Runnable openGui) {
        this.manager = manager;
        this.storage = storage;
        this.config = config;
        this.chatImportCache = chatImportCache;
        this.openGui = openGui;
        this.addFlow = new WaypointAddFlow();
    }

    public void install() {
        WaypointerCommandSuggestionOverride suggestionOverride = new WaypointerCommandSuggestionOverride();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            register(dispatcher, "waypointer");
            register(dispatcher, "wptr");
            register(dispatcher, "wp");
            dispatcher.register(literal("happysnowman").executes(ctx -> {
                HappySnowmanSession.activate();
                info(ctx.getSource(), "Happy snowman mode enabled for this server.");
                return 1;
            }));
            suggestionOverride.setClientRoot(dispatcher.getRoot().getChild("wp"));
        });
        suggestionOverride.install();
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d, String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal(root)
                .executes(ctx -> { scheduleOpenGui(); return 1; })
                .then(literal("gui").executes(ctx -> { scheduleOpenGui(); return 1; }))
                // /wp help                  -> page 1
                // /wp help <n>              -> nth page (1-based)
                // /wp help <section>        -> jump to a section by name/alias
                // /wp help all              -> show every section
                // The StringArgumentType.word() arg accepts both shapes because
                // Brigadier can't dispatch on "integer-or-word" directly -- we
                // parse it ourselves in runHelp so tab-complete can offer both
                // from a single suggestion provider.
                .then(literal("help")
                        .executes(ctx -> runHelp(ctx.getSource(), root, null))
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestHelpTargets())
                                .executes(ctx -> runHelp(ctx.getSource(), root,
                                        StringArgumentType.getString(ctx, "target")))))
                .then(literal("list").executes(ctx -> runList(ctx.getSource())))
                .then(literal("skip").executes(ctx -> runSkipCurrentWaypoint(ctx.getSource())))
                .then(literal("skipto")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestSkipTargets())
                                .executes(ctx -> runSkipTo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target")))))
                .then(currentSubwayCommand("sub"))
                .then(currentFlagCommand("tiny", Waypoint.FLAG_SMALL_SUBWAYPOINT, "Tiny", true))
                .then(currentFlagCommand("filled", Waypoint.FLAG_FILLED_SUBWAYPOINT, "Filled", true))
                .then(currentFlagCommand("hap", Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                        "Hide after parent", true))
                .then(currentFlagCommand("sts", Waypoint.FLAG_SKIP_ON_STAND, "Stand to skip", false))
                .then(currentFlagCommand("its", Waypoint.FLAG_SKIP_ON_INTERACT, "Interact to skip", false))
                .then(currentFlagCommand("los", Waypoint.FLAG_DEPTH_CHECKED, "Line-of-sight only", false))
                .then(literal("reset").executes(ctx -> runResetActiveGroup(ctx.getSource())))
                .then(literal("removerecord")
                        .then(argument("route", StringArgumentType.word())
                                .then(argument("time", LongArgumentType.longArg(0L))
                                        .executes(ctx -> runRemoveRouteRecord(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "route"),
                                                LongArgumentType.getLong(ctx, "time"))))))
                .then(literal("mode")
                        .then(argument("mode", StringArgumentType.word())
                                .suggests(suggestLoadModes())
                                .executes(ctx -> runSetActiveGroupMode(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode")))))
                .then(literal("radius")
                        .then(argument("radius", DoubleArgumentType.doubleArg(
                                Waypoint.MIN_REACH_RADIUS, Waypoint.MAX_REACH_RADIUS))
                                .executes(ctx -> runSetActiveGroupRadius(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "radius")))))
                .then(literal("move")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .then(argument("slot", IntegerArgumentType.integer(0))
                                        .suggests(suggestActiveGroupIndices())
                                        .executes(ctx -> runMoveWaypointToSlot(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                IntegerArgumentType.getInteger(ctx, "slot"))))))
                // "add" uses an explicit "at" literal for coord input so we never have to
                // disambiguate "/wp add 100" (a name) from "/wp add 100 64 200" (coords).
                // Brigadier's greedy-string fallback was flagging ambiguity warnings and --
                // more importantly -- would treat a numeric name as a failed coord parse.
                .then(literal("add")
                        .executes(ctx -> runAdd(ctx.getSource(), ""))
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestPlayerCoord(Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestPlayerCoord(Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestPlayerCoord(Axis.Z))
                                                        .executes(ctx -> runAddAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("name", StringArgumentType.greedyString())
                                                                .executes(ctx -> runAddAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "name"))))))))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runAdd(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                // "addtemp at X Y Z" is also the command fired by chat-coord clicks.
                // It diverges from "add at" in two ways: the waypoint lands in the
                // zone's dedicated temp bucket (not the active route), and it uses
                // the user's configured temp expiry default.
                .then(literal("addtemp")
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestPlayerCoord(Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestPlayerCoord(Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestPlayerCoord(Axis.Z))
                                                        .executes(ctx -> runAddTempAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("source", StringArgumentType.greedyString())
                                                                .executes(ctx -> runAddTempAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "source")))))))))
                .then(literal("chattemp")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .then(argument("sender", StringArgumentType.word())
                                                        .then(argument("source", StringArgumentType.word())
                                                                .executes(ctx -> runChatTempClick(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "sender"),
                                                                        StringArgumentType.getString(ctx, "source")))))))))
                .then(literal("blacklist")
                        .executes(ctx -> runChatCoordBlacklist(ctx.getSource()))
                        .then(literal("add")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> runChatCoordBlacklistAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(literal("remove")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> runChatCoordBlacklistRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("remove")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .executes(ctx -> runRemove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index")))))
                .then(insertCommand())
                .then(literal("clear")
                        .executes(ctx -> runClearZone(ctx.getSource(), false))
                        .then(literal("confirm").executes(ctx -> runClearZone(ctx.getSource(), true))))
                .then(literal("export")
                        .executes(ctx -> runExport(ctx.getSource(), exportOptionsFromConfig()))
                        .then(literal("names")
                                .executes(ctx -> runExport(ctx.getSource(), WaypointCodec.Options.WITH_NAMES)))
                        .then(literal("nonames")
                                .executes(ctx -> runExport(ctx.getSource(), WaypointCodec.Options.NO_NAMES))))
                .then(literal("import")
                        .executes(ctx -> runImportFromClipboard(ctx.getSource()))
                        .then(argument("payload", StringArgumentType.greedyString())
                                .suggests(suggestImportPayloads())
                                .executes(ctx -> runImportArgument(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "payload")))))
                .then(literal("importfile")
                        .then(argument("path", StringArgumentType.greedyString())
                                .suggests(suggestImportFiles())
                                .executes(ctx -> runImportFile(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "path")))))
                .then(literal("debug").executes(ctx -> { scheduleOpenDebugInspector(); return 1; }))
                .then(literal("devmode")
                        .executes(ctx -> runSetDeveloperMode(ctx.getSource(), null))
                        .then(literal("on").executes(ctx -> runSetDeveloperMode(ctx.getSource(), true)))
                        .then(literal("off").executes(ctx -> runSetDeveloperMode(ctx.getSource(), false)))
                        .then(literal("status").executes(ctx -> runDeveloperModeStatus(ctx.getSource())))
                        .then(literal("report").executes(ctx -> runDeveloperModeReport(ctx.getSource()))))
                .then(literal("editmode").executes(this::runToggleEditModeCommand))
                .then(literal("edit")
                        .then(literal("mode").executes(this::runToggleEditModeCommand)))
                .then(literal("importchat")
                        .then(argument("handle", StringArgumentType.word())
                                .suggests(suggestChatHandles())
                                .executes(ctx -> runImportChat(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "handle")))))
                .then(waypointCommand())
                .then(areaCommand())
                // "route" is the primary spelling (matches the GUI); "group"
                // stays registered so existing muscle memory and macros keep
                // working.
                .then(groupCommand("route"))
                .then(groupCommand("group"));
        d.register(cmd);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> currentSubwayCommand(String name) {
        return literal(name)
                .executes(ctx -> runToggleSubwaypoint(ctx.getSource(), null))
                .then(argument("index", IntegerArgumentType.integer(0))
                        .suggests(suggestActiveGroupIndices())
                        .executes(ctx -> runToggleSubwaypoint(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> currentFlagCommand(String name, int flag,
                                                                                String label,
                                                                                boolean subwaypointOnly) {
        return literal(name)
                .executes(ctx -> runToggleWaypointFlag(ctx.getSource(), null, flag, label, subwaypointOnly))
                .then(argument("index", IntegerArgumentType.integer(0))
                        .suggests(suggestActiveGroupIndices())
                        .executes(ctx -> runToggleWaypointFlag(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"), flag, label, subwaypointOnly)));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> insertCommand() {
        return literal("insert")
                .then(argument("index", IntegerArgumentType.integer(0))
                        .suggests(suggestInsertSlots())
                        .executes(ctx -> runInsert(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"), ""))
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestPlayerCoord(Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestPlayerCoord(Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestPlayerCoord(Axis.Z))
                                                        .executes(ctx -> runInsertAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("name", StringArgumentType.greedyString())
                                                                .executes(ctx -> runInsertAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "name"))))))))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runInsert(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"),
                                        StringArgumentType.getString(ctx, "name")))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> waypointCommand() {
        return literal("waypoint")
                .then(literal("move")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .then(literal("here")
                                        .executes(ctx -> runMoveWaypointHere(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"))))
                                .then(literal("at")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .suggests(suggestPlayerCoord(Axis.X))
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .suggests(suggestPlayerCoord(Axis.Y))
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .suggests(suggestPlayerCoord(Axis.Z))
                                                                .executes(ctx -> runMoveWaypointAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))))
                .then(literal("rename")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runRenameWaypoint(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("color")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .then(argument("hex", StringArgumentType.word())
                                        .suggests(suggestHexColors())
                                        .executes(ctx -> runSetWaypointColor(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "hex"))))))
                .then(literal("radius")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .then(argument("radius", DoubleArgumentType.doubleArg(
                                        0.0, Waypoint.MAX_REACH_RADIUS))
                                        .executes(ctx -> runSetWaypointRadius(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(literal("sub")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .executes(ctx -> runToggleSubwaypoint(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index")))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> areaCommand() {
        return literal("area")
                .then(argument("group", IntegerArgumentType.integer(0))
                        .suggests(suggestAllGroupIndices())
                        .then(literal("current")
                                .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "group"), "current")))
                        .then(argument("zone", StringArgumentType.greedyString())
                                .suggests(suggestZoneTargets())
                                .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "group"),
                                        StringArgumentType.getString(ctx, "zone")))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> groupCommand(String literalName) {
        return literal(literalName)
                .then(literal("create")
                        .then(argument("name", StringArgumentType.greedyString())
                                .suggests(suggestGroupNames())
                                .executes(ctx -> runCreateGroup(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("list").executes(ctx -> runListGroups(ctx.getSource())))
                .then(literal("rename")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runRenameGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("zone")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(literal("current")
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), "current")))
                                .then(argument("zone", StringArgumentType.greedyString())
                                        .suggests(suggestZoneTargets())
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "zone"))))))
                .then(literal("area")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(literal("current")
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), "current")))
                                .then(argument("zone", StringArgumentType.greedyString())
                                        .suggests(suggestZoneTargets())
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "zone"))))))
                .then(literal("mode")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests(suggestLoadModes())
                                        .executes(ctx -> runSetGroupMode(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "mode"))))))
                .then(literal("radius")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("radius", DoubleArgumentType.doubleArg(
                                        Waypoint.MIN_REACH_RADIUS, Waypoint.MAX_REACH_RADIUS))
                                        .executes(ctx -> runSetGroupRadius(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(literal("skipahead")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupSkipAhead(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), "toggle"))
                                .then(argument("state", StringArgumentType.word())
                                        .suggests(suggestToggleStates())
                                        .executes(ctx -> runSetGroupSkipAhead(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "state"))))))
                .then(literal("enable")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupEnabled(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), true))))
                .then(literal("disable")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupEnabled(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), false))))
                .then(literal("colormode")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests(suggestColorModes())
                                        .executes(ctx -> runSetGroupColorMode(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "mode"))))))
                .then(literal("color")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("hex", StringArgumentType.word())
                                        .suggests(suggestHexColors())
                                        .executes(ctx -> runSetGroupStaticColor(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "hex"))))))
                .then(literal("gradient")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .then(argument("start", StringArgumentType.word())
                                        .suggests(suggestHexColors())
                                        .then(argument("end", StringArgumentType.word())
                                                .suggests(suggestHexColors())
                                                .executes(ctx -> runSetGroupGradient(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                        StringArgumentType.getString(ctx, "start"),
                                                        StringArgumentType.getString(ctx, "end")))))))
                .then(literal("delete")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestAllGroupIndices())
                                .executes(ctx -> runDeleteGroup(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), false))
                                .then(literal("confirm")
                                        .executes(ctx -> runDeleteGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), true)))));
    }

    // --- tab-complete suggestion providers ----------------------------------------------------

    /**
     * Suggests zero-based numeric indices into the first active group's waypoints.
     * Tooltips include the matching display label so the split between command
     * indices and {@code /wp skipto} labels is visible while tab-completing.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestActiveGroupIndices() {
        return (ctx, builder) -> {
            WaypointGroup g = manager.firstActiveGroup();
            if (g == null) return builder.buildFuture();
            return CommandHelpers.suggestIndexed(builder, g.size(),
                    i -> activeGroupIndexTooltip(g, i));
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestSkipTargets() {
        return (ctx, builder) -> {
            suggestSkipTargets(manager.activeGroups(), builder);
            return builder.buildFuture();
        };
    }

    static int suggestSkipTargets(List<WaypointGroup> groups, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        Set<String> suggested = new LinkedHashSet<>();
        for (WaypointGroup group : groups) {
            if (group == null) continue;
            for (int i = 0; i < group.size(); i++) {
                String label = skipTargetLabel(group, i);
                if (label.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    if (suggested.add(label)) {
                        builder.suggest(label, Component.literal(describeWaypoint(group.get(i))));
                    }
                }
            }
        }
        return suggested.size();
    }

    /**
     * Suggests every legal insertion slot 0..size (inclusive of size, since
     * insert(size, w) is equivalent to add and is a useful nudge that "the end"
     * is also a valid target). Tooltips read as "before [n]" so the index's
     * meaning is unambiguous -- inserting at 2 means the new point becomes
     * the new index 2, displacing whatever was there.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestInsertSlots() {
        return (ctx, builder) -> {
            WaypointGroup g = manager.firstActiveGroup();
            if (g == null) return builder.buildFuture();
            int size = g.size();
            String prefix = builder.getRemaining();
            for (int i = 0; i <= size; i++) {
                String s = Integer.toString(i);
                if (!s.startsWith(prefix)) continue;
                String tip = i == size
                        ? "0-based slot " + i + " appends"
                        : "0-based slot " + i + ", before #"
                                + g.displayIndexLabel(i) + " " + describeWaypoint(g.get(i));
                builder.suggest(i, Component.literal(tip));
            }
            return builder.buildFuture();
        };
    }

    /**
     * Suggests zero-based numeric indices into the full group list, with each group's
     * name + point count as a tooltip so an accidental {@code delete 4} is
     * harder to mis-fire.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestAllGroupIndices() {
        return (ctx, builder) -> {
            List<WaypointGroup> all = manager.allGroupsList();
            return CommandHelpers.suggestIndexed(builder, all.size(),
                    i -> "index " + i + ": " + all.get(i).name()
                            + " (" + all.get(i).size() + " pts)");
        };
    }

    /**
     * Suggests currently-live chat import handles. The cache evicts on its
     * own LRU schedule; we snapshot it here so the completion list only ever
     * contains handles that still resolve to a payload.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestChatHandles() {
        return (ctx, builder) -> {
            List<String> handles = chatImportCache.handles();
            String token = builder.getRemainingLowerCase();
            for (String h : handles) {
                if (h.toLowerCase(Locale.ROOT).startsWith(token)) {
                    builder.suggest(h, Component.literal("cached import"));
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestPlayerCoord(Axis axis) {
        return (ctx, builder) -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return builder.buildFuture();
            PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                    player.getX(), player.getY(), player.getZ(), config);
            int value = switch (axis) {
                case X -> pos.x();
                case Y -> pos.y();
                case Z -> pos.z();
            };
            String raw = Integer.toString(value);
            if (raw.startsWith(builder.getRemaining())) {
                builder.suggest(value, Component.literal(playerCoordSuggestionLabel(axis)));
            }
            return builder.buildFuture();
        };
    }

    private String playerCoordSuggestionLabel(Axis axis) {
        if (axis == Axis.Y && config.placeNewWaypointsBelowPlayer()) {
            return "current player y - 1";
        }
        return "current player " + axis.name().toLowerCase(Locale.ROOT);
    }

    private SuggestionProvider<FabricClientCommandSource> suggestImportPayloads() {
        return (ctx, builder) -> {
            for (String handle : chatImportCache.handles()) {
                CommandHelpers.suggestText(builder, handle, "cached chat import");
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestImportFiles() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, storage.file().toString(), "current waypoint storage file");
            Path parent = storage.file().getParent();
            if (parent != null) CommandHelpers.suggestText(builder, parent.toString(), "waypointer config folder");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestGroupNames() {
        return (ctx, builder) -> {
            Zone zone = manager.currentZone();
            if (zone != null) {
                CommandHelpers.suggestText(builder, "Route -- " + zone.displayName().toLowerCase(Locale.ROOT),
                        "default route name for current zone");
            }
            CommandHelpers.suggestText(builder, "Route", "generic route name");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestLoadModes() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "sequence", "show previous/current/next route points");
            CommandHelpers.suggestText(builder, "static", "show every waypoint at once");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestColorModes() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "gradient", "auto color waypoints between endpoints");
            CommandHelpers.suggestText(builder, "manual", "keep per-waypoint colors");
            CommandHelpers.suggestText(builder, "one", "use one route color");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestToggleStates() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "toggle", "flip current state");
            CommandHelpers.suggestText(builder, "on", "turn on");
            CommandHelpers.suggestText(builder, "off", "turn off");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestZoneTargets() {
        return (ctx, builder) -> {
            Zone current = manager.currentZone();
            if (current != null) {
                CommandHelpers.suggestText(builder, "current", "current area: " + current.displayName());
                CommandHelpers.suggestText(builder, current.id(), current.displayName());
            }
            for (String zoneId : manager.knownZoneIds()) {
                CommandHelpers.suggestText(builder, zoneId, Zone.fromId(zoneId).displayName());
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestHexColors() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "4FE05A", "Waypointer green");
            CommandHelpers.suggestText(builder, "00BFFF", "cool route start");
            CommandHelpers.suggestText(builder, "FF3040", "hot route end");
            return builder.buildFuture();
        };
    }

    private static String describeWaypoint(Waypoint w) {
        String coords = w.x() + ", " + w.y() + ", " + w.z();
        return w.hasName() ? w.name() + "  " + coords : coords;
    }

    static String activeGroupIndexTooltip(WaypointGroup group, int index) {
        return "index " + index + " (" + group.displayIndexLabel(index) + ") "
                + describeWaypoint(group.get(index));
    }

    private enum Axis { X, Y, Z }

    // --- subcommands --------------------------------------------------------------------------

    // Client commands execute on the network thread; opening a screen touches render-thread
    // state and silently no-ops (or crashes) if called from the wrong thread. Schedule it.
    private void scheduleOpenGui() {
        Minecraft.getInstance().execute(openGui);
    }

    private void scheduleOpenDebugInspector() {
        // Screen is opened standalone (no parent) so Escape closes the entire screen
        // stack rather than dropping the user into whatever they had open before --
        // /wp debug is a diagnostic entry point, not a sub-view of the main GUI.
        Minecraft.getInstance().execute(() -> DebugInspectScreen.open(null, manager, config));
    }

    private int runSetDeveloperMode(FabricClientCommandSource src, Boolean requestedState) {
        var monitor = WaypointerClient.developerModeMonitor();
        if (monitor == null) {
            error(src, "Developer mode is unavailable because dungeon diagnostics did not initialize.");
            return 0;
        }
        boolean enable = requestedState == null ? !monitor.enabled() : requestedState;
        try {
            Path file = enable ? monitor.enable() : monitor.disable();
            if (enable) {
                success(src, "Developer mode enabled for this session. Log: " + file);
            } else {
                success(src, "Developer mode disabled. Log: " + (file == null ? "(none)" : file));
            }
            return 1;
        } catch (RuntimeException e) {
            Waypointer.LOGGER.error("Could not change developer mode", e);
            error(src, "Could not change developer mode. See latest.log for details.");
            return 0;
        }
    }

    private int runDeveloperModeStatus(FabricClientCommandSource src) {
        var monitor = WaypointerClient.developerModeMonitor();
        if (monitor == null) {
            error(src, "Developer mode is unavailable because dungeon diagnostics did not initialize.");
            return 0;
        }
        info(src, monitor.statusLine());
        return 1;
    }

    private int runDeveloperModeReport(FabricClientCommandSource src) {
        var monitor = WaypointerClient.developerModeMonitor();
        if (monitor == null || !monitor.enabled()) {
            warn(src, "Developer mode is off. Use /wp devmode on first.");
            return 0;
        }
        monitor.writeReport("manual command");
        success(src, "Developer report written to " + monitor.logFile());
        return 1;
    }

    private int runToggleEditModeCommand(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (manager == null || config == null) {
            error(src, "Edit mode is unavailable until Waypointer has loaded routes.");
            return 0;
        }

        WaypointRepositionMode.toggleEditMode(manager, config);
        return 1;
    }

    /**
     * Target left-column width for help rows. Chosen to match the longest usage
     * we print ("/wp export [names|nonames]" at 26 chars) plus one space of
     * breathing room before the " -- " separator. Chat is not a monospace
     * surface, but padding to a consistent character column still visibly
     * aligns the descriptions because spaces render at a fixed narrow pixel
     * width and the usage lines are all ASCII.
     */
    /**
     * Paginated help is organized by topic: each {@link HelpSection} is one
     * page. Keeping sections small (4-5 rows) means every page fits on screen
     * without scrolling chat, and the section titles double as jump targets
     * (e.g. {@code /wp help groups}). Order here is the order pages render;
     * first entry is page 1.
     *
     * When adding a new subcommand, put its row in the section that best matches
     * its purpose rather than appending to the tail -- help reads badly when
     * related commands live on different pages.
     */
    private static final List<HelpSection> HELP_SECTIONS = List.of(
            new HelpSection("basics", "Basics",
                    List.of(
                            new HelpRow("", "Open the Waypointer editor.", "", "gui"),
                            new HelpRow(" gui", "Open the Waypointer editor.", "gui"),
                            new HelpRow(" list", "List active routes and their waypoints.", "list"),
                            new HelpRow(" help [all|section|command]", "Show all help or jump to one topic.",
                                    "help", "help subway"))),
            new HelpSection("route", "Route editing",
                    List.of(
                            new HelpRow(" add [name]", "Add a waypoint at your current block position.",
                                    "add", "add Fairy Soul"),
                            new HelpRow(" add at <x> <y> <z> [name]", "Add a waypoint at exact block coordinates.",
                                    "add at 12 70 -4", "add at 12 70 -4 Lever"),
                            new HelpRow(" insert <slot> [name]", "Insert a waypoint at your position before a 0-based slot.",
                                    "insert 2", "insert 2 Secret"),
                            new HelpRow(" insert <slot> at <x> <y> <z> [name]", "Insert a waypoint at exact coordinates.",
                                    "insert 2 at 12 70 -4", "insert 2 at 12 70 -4 Chest"),
                            new HelpRow(" remove <index>", "Remove a waypoint by 0-based index.",
                                    "remove 3"),
                            new HelpRow(" move <index> <slot>", "Reorder a waypoint or main-waypoint block.",
                                    "move 4 2"),
                            new HelpRow(" skipto <n[.sub]>", "Jump active routes to a displayed waypoint label.",
                                    "skipto 3", "skipto 3.2"),
                            new HelpRow(" skip", "Advance active routes by one waypoint.",
                                    "skip"),
                            new HelpRow(" reset", "Reset the active route to its first waypoint.",
                                    "reset"),
                            new HelpRow(" mode <static|sequence>", "Set active route visibility mode.",
                                    "mode sequence", "mode static"),
                            new HelpRow(" radius <blocks>", "Set active route reach radius.",
                                    "radius 4.5"),
                            new HelpRow(" editmode", "Toggle in-world edit mode.",
                                    "editmode"),
                            new HelpRow(" edit mode", "Toggle in-world edit mode.",
                                    "edit mode"),
                            new HelpRow(" clear [confirm]", "Delete all routes in the current area after confirmation.",
                                    "clear", "clear confirm"))),
            new HelpSection("subway", "Subwaypoints & waypoint flags",
                    List.of(
                            new HelpRow(" sub [index]", "Toggle the current or indexed waypoint as a subwaypoint.",
                                    "sub", "sub 4"),
                            new HelpRow(" tiny [index]", "Toggle tiny 1/16-block rendering on a subwaypoint.",
                                    "tiny", "tiny 4"),
                            new HelpRow(" filled [index]", "Toggle filled-box rendering on a subwaypoint.",
                                    "filled", "filled 4"),
                            new HelpRow(" hap [index]", "Hide a subwaypoint after its parent waypoint is reached.",
                                    "hap", "hap 4"),
                            new HelpRow(" sts [index]", "Toggle dungeon stand-to-skip on the current or indexed waypoint.",
                                    "sts", "sts 4"),
                            new HelpRow(" its [index]", "Toggle dungeon interact-to-skip on the current or indexed waypoint.",
                                    "its", "its 4"),
                            new HelpRow(" los [index]", "Render the current or indexed waypoint only when line-of-sight passes.",
                                    "los", "los 4"))),
            new HelpSection("waypoint", "Waypoint details",
                    List.of(
                            new HelpRow(" waypoint move <index> here", "Move a waypoint to your current block position.",
                                    "waypoint move 3 here"),
                            new HelpRow(" waypoint move <index> at <x> <y> <z>", "Move a waypoint to exact block coordinates.",
                                    "waypoint move 3 at 12 70 -4"),
                            new HelpRow(" waypoint rename <index> <name>", "Rename one waypoint.",
                                    "waypoint rename 3 Fairy Soul"),
                            new HelpRow(" waypoint color <index> <hex>", "Set and lock one waypoint color.",
                                    "waypoint color 3 58C878"),
                            new HelpRow(" waypoint radius <index> <blocks>", "Override one waypoint reach radius.",
                                    "waypoint radius 3 1.5"),
                            new HelpRow(" waypoint sub <index>", "Toggle an indexed waypoint as a subwaypoint.",
                                    "waypoint sub 4"))),
            new HelpSection("routes", "Routes & areas",
                    List.of(
                            new HelpRow(" route create <name>", "Create a new route in the current area.",
                                    "route create Foraging Route"),
                            new HelpRow(" route list", "List every route across all areas.",
                                    "route list"),
                            new HelpRow(" route rename <index> <name>", "Rename a route by route-list index.",
                                    "route rename 1 Park Route"),
                            new HelpRow(" route zone <index> <zone|current>", "Attach a route to a zone or the current area.",
                                    "route zone 1 current", "route zone 1 the_park"),
                            new HelpRow(" route area <index> <zone|current>", "Alias for attaching a route to an area.",
                                    "route area 1 current"),
                            new HelpRow(" area <route> <zone|current>", "Short form for attaching a route to an area.",
                                    "area 1 current"),
                            new HelpRow(" route mode <index> <static|sequence>", "Set one route's visibility mode.",
                                    "route mode 1 static"),
                            new HelpRow(" route radius <index> <blocks>", "Set one route's reach radius.",
                                    "route radius 1 4.5"),
                            new HelpRow(" route skipahead <index> [on|off|toggle]", "Control skip-ahead for one route.",
                                    "route skipahead 1 off"),
                            new HelpRow(" route enable <index>", "Enable one route.",
                                    "route enable 1"),
                            new HelpRow(" route disable <index>", "Disable one route.",
                                    "route disable 1"),
                            new HelpRow(" route colormode <index> <one|gradient|manual>", "Set one route's color mode.",
                                    "route colormode 1 gradient"),
                            new HelpRow(" route color <index> <hex>", "Set one route's single color.",
                                    "route color 1 4FE05A"),
                            new HelpRow(" route gradient <index> <start> <end>", "Set one route's gradient endpoints.",
                                    "route gradient 1 00BFFF FF3040"),
                            new HelpRow(" route delete <index> [confirm]", "Delete a route by route-list index after confirmation.",
                                    "route delete 1", "route delete 1 confirm"),
                            new HelpRow(" group ...", "Alias: every route subcommand also works as /wp group.",
                                    "group list"))),
            new HelpSection("sharing", "Sharing (import/export)",
                    List.of(
                            new HelpRow(" export [names|nonames]", "Copy current-area routes to the clipboard as a codec.",
                                    "export", "export names"),
                            new HelpRow(" import [payload]", "Import from the clipboard or an inline payload.",
                                    "import", "import WP:..."),
                            new HelpRow(" importfile <path>", "Import a waypoint JSON file from disk.",
                                    "importfile C:\\routes\\waypoints.json"),
                            new HelpRow(" importchat <handle>", "Import from a cached chat pill.",
                                    "importchat A1b2"))),
            new HelpSection("chat", "Temporary & chat waypoints",
                    List.of(
                            new HelpRow(" addtemp at <x> <y> <z> [source]", "Add a temporary waypoint at coordinates.",
                                    "addtemp at 12 70 -4 Party"),
                            new HelpRow(" chattemp <x> <y> <z> <sender> <source>", "Handle a detected chat-coordinate click.",
                                    "chattemp 12 70 -4 Babbur party"),
                            new HelpRow(" blacklist", "Show the chat waypoint sender blacklist.",
                                    "blacklist"),
                            new HelpRow(" blacklist add <name>", "Block detected coordinates from a sender.",
                                    "blacklist add Babbur"),
                            new HelpRow(" blacklist remove <name>", "Allow detected coordinates from a sender again.",
                                    "blacklist remove Babbur"))),
            new HelpSection("debug", "Debug",
                    List.of(
                            new HelpRow(" debug", "Open the all-in-one troubleshooting report.",
                                    "debug"),
                            new HelpRow(" devmode [on|off|status|report]",
                                    "Monitor dungeon room detection and write diagnostic reports.",
                                    "devmode on", "devmode report")))
    );

    /**
     * Render one page of the paginated help.
     *
     * @param target null â†’ page 1; digits â†’ that page (1-based); otherwise a
     *               section id / title substring.
     */
    private int runHelp(FabricClientCommandSource src, String root, String target) {
        String prefix = "/" + root;
        if (target != null && "all".equalsIgnoreCase(target.trim())) {
            info(src, Component.literal("Waypointer help")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  hover commands for details")
                            .withStyle(ChatFormatting.DARK_GRAY)));
            for (HelpSection section : HELP_SECTIONS) {
                renderHelpSection(src, prefix, section);
            }
            renderHelpFooter(src, root, -1);
            return 1;
        }

        int pageIdx = resolveHelpPage(target);
        if (pageIdx < 0) {
            error(src, "Unknown help section: '" + target + "'. Try /" + root + " help.");
            return 0;
        }

        HelpSection section = HELP_SECTIONS.get(pageIdx);

        info(src, Component.literal("Waypointer help: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(section.title()).withStyle(ChatFormatting.WHITE)));

        renderHelpSection(src, prefix, section);

        renderHelpFooter(src, root, pageIdx);
        return 1;
    }

    /**
     * Resolves a user-supplied help target to a zero-based section index, or
     * -1 for an unrecognized target. {@code null} (no arg) is page 1. Digits
     * map to the 1-based page number. Any other word is matched against
     * section ids, title prefixes, and the first word in each help row,
     * case-insensitively, so both {@code /wp help groups} and
     * {@code /wp help import} land on useful pages.
     */
    private static int resolveHelpPage(String target) {
        if (target == null || target.isBlank()) return 0;
        String t = target.trim().toLowerCase(Locale.ROOT);
        if ("editing".equals(t)) return 1;
        if ("flags".equals(t)) return 2;
        if ("areas".equals(t)) return 4;

        if (t.chars().allMatch(Character::isDigit)) {
            int page = Integer.parseInt(t) - 1;
            return (page >= 0 && page < HELP_SECTIONS.size()) ? page : -1;
        }

        for (int i = 0; i < HELP_SECTIONS.size(); i++) {
            HelpSection s = HELP_SECTIONS.get(i);
            if (s.id().equals(t) || s.title().toLowerCase(Locale.ROOT).startsWith(t)) return i;
            for (HelpRow row : s.rows()) {
                String usage = row.usage().trim().toLowerCase(Locale.ROOT);
                int firstSpace = usage.indexOf(' ');
                String commandWord = firstSpace < 0 ? usage : usage.substring(0, firstSpace);
                if (commandWord.equals(t)) return i;
            }
        }
        return -1;
    }

    /**
     * Builds the clickable page-nav footer. Prev/next use {@link
     * ClickEvent.RunCommand} so one click advances the page, rather than
     * SuggestCommand which would only prefill chat. Inactive arrows (on page
     * boundaries) render dim and stay unclickable -- handing them a
     * RunCommand to an out-of-range page would just print an error.
     */
    private void renderHelpSection(FabricClientCommandSource src, String prefix, HelpSection section) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                Component.literal(section.title()).withStyle(ChatFormatting.YELLOW)));
        for (HelpRow row : section.rows()) {
            helpLine(src, prefix, row);
        }
    }

    private void renderHelpFooter(FabricClientCommandSource src, String root, int pageIdx) {
        MutableComponent footer = Component.empty();

        footer.append(Component.literal("sections: ").withStyle(ChatFormatting.DARK_GRAY));
        for (int i = 0; i < HELP_SECTIONS.size(); i++) {
            HelpSection s = HELP_SECTIONS.get(i);
            boolean current = i == pageIdx;
            MutableComponent jump = Component.literal(s.id());
            jump.withStyle(current
                    ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                    : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/" + root + " help " + s.id()))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                    Component.literal(s.title()))));
            footer.append(jump);
            footer.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        }

        MutableComponent all = Component.literal("all");
        all.withStyle(pageIdx < 0
                ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + root + " help all"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.literal("Show every command"))));
        footer.append(all);
        src.sendFeedback(WaypointerChatFeedback.suppress(footer));
    }

    /**
     * Tab-completions for {@code /wp help <target>}: every page number
     * 1..N and every section id. Page numbers come first so hitting Tab
     * after "/wp help " surfaces them immediately -- that's the fastest
     * path for users who already know the page they want.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestHelpTargets() {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            CommandHelpers.suggestText(builder, "all", "show every command");
            for (int i = 0; i < HELP_SECTIONS.size(); i++) {
                String n = Integer.toString(i + 1);
                if (n.startsWith(prefix)) {
                    builder.suggest(n, Component.literal(HELP_SECTIONS.get(i).title()));
                }
            }
            for (HelpSection s : HELP_SECTIONS) {
                if (s.id().startsWith(prefix)) {
                    builder.suggest(s.id(), Component.literal(s.title()));
                }
            }
            Set<String> commandWords = new LinkedHashSet<>();
            for (HelpSection s : HELP_SECTIONS) {
                for (HelpRow row : s.rows()) {
                    String commandWord = helpCommandWord(row.usage());
                    if (!commandWord.isEmpty() && commandWords.add(commandWord)
                            && commandWord.startsWith(prefix)) {
                        builder.suggest(commandWord, Component.literal(s.title()));
                    }
                }
            }
            CommandHelpers.suggestText(builder, "all", "show every command");
            return builder.buildFuture();
        };
    }

    private static void helpLine(FabricClientCommandSource src, String prefix, HelpRow row) {
        MutableComponent line = highlightedCommand(prefix, row.usage());
        line.withStyle(line.getStyle().withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                helpHover(prefix, row))));
        src.sendFeedback(WaypointerChatFeedback.suppress(line));
    }

    private static Component helpHover(String prefix, HelpRow row) {
        MutableComponent hover = Component.empty();
        hover.append(Component.literal(row.description()).withStyle(ChatFormatting.YELLOW));
        hover.append(Component.literal("\n\nUsage:\n").withStyle(ChatFormatting.AQUA));
        hover.append(highlightedCommand(prefix, row.usage()));
        if (!row.examples().isEmpty()) {
            hover.append(Component.literal("\n\nExample(s):").withStyle(ChatFormatting.GREEN));
            for (String example : row.examples()) {
                hover.append(Component.literal("\n").withStyle(ChatFormatting.GRAY));
                hover.append(highlightedCommand(prefix, example));
            }
        }
        return hover;
    }

    private static MutableComponent highlightedCommand(String prefix, String usage) {
        MutableComponent out = Component.literal(prefix)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
        String trimmed = usage == null ? "" : usage.trim();
        if (trimmed.isEmpty()) return out;
        for (String token : trimmed.split(" ")) {
            if (token.isEmpty()) continue;
            out.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
            out.append(highlightedToken(token));
        }
        return out;
    }

    private static MutableComponent highlightedToken(String token) {
        ChatFormatting color;
        if (token.startsWith("<") && token.endsWith(">")) {
            color = ChatFormatting.GREEN;
        } else if (token.startsWith("[") && token.endsWith("]")) {
            color = ChatFormatting.GRAY;
        } else if (token.contains("|")) {
            color = ChatFormatting.LIGHT_PURPLE;
        } else {
            color = ChatFormatting.WHITE;
        }
        return Component.literal(token).withStyle(color);
    }

    private static String helpCommandWord(String usage) {
        String trimmed = usage == null ? "" : usage.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "";
        int firstSpace = trimmed.indexOf(' ');
        return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    }

    /** One row in a help section: the usage shape (minus the root prefix) and what it does. */
    private record HelpRow(String usage, String description, List<String> examples) {
        HelpRow(String usage, String description, String... examples) {
            this(usage, description, List.of(examples));
        }
    }

    /**
     * One page of help. {@code id} is the short lookup key ({@code "groups"}),
     * {@code title} is the on-screen heading ({@code "Groups & debug"}).
     * Kept separate so title changes don't invalidate users' muscle memory
     * for the jump commands.
     */
    private record HelpSection(String id, String title, List<HelpRow> rows) {}

    private int runList(FabricClientCommandSource src) {
        List<WaypointGroup> active = manager.activeGroups();
        if (active.isEmpty()) {
            info(src, "No active routes in this zone" + zoneSuffix());
            return 0;
        }
        for (WaypointGroup g : active) {
            String currentIndexText = Integer.toString(g.currentIndex());
            if (g.currentIndex() >= 0 && g.currentIndex() < g.size()) {
                currentIndexText += " (#" + g.displayIndexLabel(g.currentIndex()) + ")";
            }
            info(src, Component.literal("Route: ")
                    .append(Component.literal(g.name()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (" + g.size()
                            + " points, current index " + currentIndexText + ")")
                            .withStyle(ChatFormatting.GRAY)));
            int shown = Math.min(g.size(), 16);
            for (int i = 0; i < shown; i++) {
                Waypoint w = g.get(i);
                ChatFormatting color = i < g.currentIndex() ? ChatFormatting.DARK_GRAY
                        : i == g.currentIndex() ? ChatFormatting.YELLOW
                        : ChatFormatting.WHITE;
                info(src, Component.literal("  [#" + g.displayIndexLabel(i) + " / index " + i + "] ")
                        .append(Component.literal(w.x() + ", " + w.y() + ", " + w.z()).withStyle(color))
                        .append(w.hasName() ? Component.literal(" " + w.name()).withStyle(ChatFormatting.GRAY)
                                : Component.empty()));
            }
            if (g.size() > shown) info(src, "  ... " + (g.size() - shown) + " more");
        }
        return active.size();
    }

    private int runSkipCurrentWaypoint(FabricClientCommandSource src) {
        int moved = WaypointerKeybinds.skipCurrentWaypointTargets(
                manager, config, System.currentTimeMillis());
        if (moved == 0) {
            error(src, "No active route to skip in.");
            return 0;
        }
        success(src, "Skipped the current waypoint in " + moved + " active route"
                + (moved == 1 ? "." : "s."));
        return moved;
    }

    private int runSkipTo(FabricClientCommandSource src, String target) {
        List<WaypointGroup> activeGroups = manager.activeGroups();
        if (activeGroups.isEmpty()) {
            error(src, "No active route to skip in");
            return 0;
        }
        SkipToOutcome outcome = skipActiveGroupsToTarget(activeGroups, target);
        if (outcome.moved() == 0) {
            error(src, outcome.error() == null
                    ? "No active route has target '" + target + "'."
                    : outcome.error());
            return 0;
        }
        manager.fireDataChanged();

        success(src, "Skipped " + outcome.moved() + " active route"
                + (outcome.moved() == 1 ? "" : "s")
                + " to " + outcome.firstMovedLabel());
        return outcome.moved();
    }

    static SkipToOutcome skipActiveGroupsToTarget(List<WaypointGroup> activeGroups, String target) {
        int moved = 0;
        String firstError = null;
        String firstMovedLabel = null;
        for (WaypointGroup group : activeGroups) {
            if (group == null || group.isEmpty()) continue;
            SkipTarget resolved = resolveSkipTargetIndex(group, target);
            if (resolved.error() != null) {
                if (firstError == null) firstError = resolved.error();
                continue;
            }
            group.setCurrentTargetIndex(resolved.index());
            if (firstMovedLabel == null) {
                firstMovedLabel = skipTargetLabel(group, resolved.index());
            }
            moved++;
        }
        return new SkipToOutcome(moved, firstMovedLabel, firstError);
    }

    private static SkipTarget resolveSkipTargetIndex(WaypointGroup group, String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.startsWith("#")) target = target.substring(1);
        if (target.isEmpty()) {
            return SkipTarget.error("Usage: /wp skipto <number>, for example 2 or 2.2");
        }

        String[] parts = target.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            return SkipTarget.error("Invalid skip target '" + rawTarget + "'. Use 2 or 2.2.");
        }

        int mainOrdinal = parsePositiveOrdinal(parts[0]);
        if (mainOrdinal <= 0) {
            return SkipTarget.error("Main waypoint number must be 1 or higher.");
        }

        int mainIndex = indexForMainOrdinal(group, mainOrdinal);
        if (mainIndex < 0) {
            return SkipTarget.error("Main waypoint " + mainOrdinal
                    + " out of range (1.." + group.mainWaypointCount() + ")");
        }
        if (parts.length == 1) {
            return SkipTarget.index(mainIndex);
        }
        if (parts[1].isBlank()) {
            return SkipTarget.error("Subwaypoint number is missing after the decimal.");
        }

        int childOrdinal = parsePositiveOrdinal(parts[1]);
        if (childOrdinal <= 0) {
            return SkipTarget.error("Subwaypoint number must be 1 or higher.");
        }

        int childIndex = indexForChildOrdinal(group, mainIndex, childOrdinal);
        if (childIndex < 0) {
            return SkipTarget.error("Waypoint " + mainOrdinal + " only has "
                    + childCount(group, mainIndex) + " subwaypoint(s).");
        }
        return SkipTarget.index(childIndex);
    }

    private static int parsePositiveOrdinal(String raw) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 ? -1 : parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int indexForMainOrdinal(WaypointGroup group, int ordinal) {
        int count = 0;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i)) continue;
            count++;
            if (count == ordinal) return i;
        }
        return -1;
    }

    private static int indexForChildOrdinal(WaypointGroup group, int mainIndex, int childOrdinal) {
        if (mainIndex < 0 || mainIndex >= group.size() || group.isSubwaypoint(mainIndex)) {
            return -1;
        }
        int count = 0;
        for (int i = mainIndex + 1; i < group.size() && group.isSubwaypoint(i); i++) {
            count++;
            if (count == childOrdinal) return i;
        }
        return -1;
    }

    private static int childCount(WaypointGroup group, int mainIndex) {
        if (mainIndex < 0 || mainIndex >= group.size() || group.isSubwaypoint(mainIndex)) {
            return 0;
        }
        int count = 0;
        for (int i = mainIndex + 1; i < group.size() && group.isSubwaypoint(i); i++) {
            count++;
        }
        return count;
    }

    private static String skipTargetLabel(WaypointGroup group, int index) {
        String label = group.displayIndexLabel(index);
        return label.startsWith("#") ? label.substring(1) : label;
    }

    record SkipToOutcome(int moved, String firstMovedLabel, String error) {}

    private record SkipTarget(int index, String error) {
        static SkipTarget index(int index) {
            return new SkipTarget(index, null);
        }

        static SkipTarget error(String message) {
            return new SkipTarget(-1, message == null ? "Invalid skip target" : message);
        }
    }

    private int runResetActiveGroup(FabricClientCommandSource src) {
        WaypointGroup group = manager.firstActiveGroup();
        if (group == null) {
            error(src, "No active route in the current area.");
            return 0;
        }
        group.resetProgress();
        manager.fireDataChanged();
        success(src, "Reset \"" + group.name() + "\" to the first waypoint");
        return 1;
    }

    private int runRemoveRouteRecord(FabricClientCommandSource src,
                                     String encodedGroupId,
                                     long expectedTimeMillis) {
        String groupId;
        try {
            groupId = new String(Base64.getUrlDecoder().decode(encodedGroupId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidId) {
            error(src, "That route record link is invalid.");
            return 0;
        }

        WaypointGroup group = manager.get(groupId);
        if (group == null || !group.removeBestTimeMillis(expectedTimeMillis)) {
            error(src, "That route record has already changed or been removed.");
            return 0;
        }

        manager.fireDataChangedFor(group);
        success(src, "Removed the best time for \"" + group.name() + "\".");
        return 1;
    }

    private int runSetActiveGroupMode(FabricClientCommandSource src, String rawMode) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (mode == null) {
            error(src, "Mode must be static or sequence.");
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, "Set \"" + group.name() + "\" mode to " + mode.name().toLowerCase(Locale.ROOT));
        return 1;
    }

    private int runSetActiveGroupRadius(FabricClientCommandSource src, double radius) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, "Set \"" + group.name() + "\" reach radius to "
                + String.format(Locale.ROOT, "%.1f", group.defaultRadius()));
        return 1;
    }

    private int runMoveWaypointToSlot(FabricClientCommandSource src, int index, int slot) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        if (slot < 0 || slot >= group.size()) {
            error(src, "0-based move slot " + slot
                    + " out of range (0.." + (group.size() - 1) + ")");
            return 0;
        }
        group.move(index, slot);
        manager.fireDataChanged();
        success(src, "Moved waypoint index " + index + " toward slot " + slot);
        return 1;
    }

    private int runToggleSubwaypoint(FabricClientCommandSource src, Integer requestedIndex) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;

        boolean wasSubwaypoint = group.isSubwaypoint(index);
        if (!group.toggleSubwaypoint(index)) {
            error(src, index == 0
                    ? "The first waypoint cannot be a subwaypoint."
                    : "Waypoint index " + index + " cannot be made into a subwaypoint.");
            return 0;
        }
        if (!wasSubwaypoint && group.isSubwaypoint(index)) {
            group.setSkipAheadEnabled(false);
        }
        manager.fireDataChanged();
        success(src, "Waypoint " + group.displayIndexLabel(index)
                + (group.isSubwaypoint(index) ? " is now a subwaypoint" : " is now a main waypoint"));
        return 1;
    }

    private int runToggleWaypointFlag(FabricClientCommandSource src, Integer requestedIndex,
                                      int flag, String label, boolean subwaypointOnly) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;
        if (subwaypointOnly && !group.isSubwaypoint(index)) {
            error(src, "Waypoint " + group.displayIndexLabel(index)
                    + " is not a subwaypoint. Run /wp sub " + index + " first.");
            return 0;
        }

        Waypoint waypoint = group.get(index);
        int nextFlags = waypoint.flags() ^ flag;
        group.set(index, waypoint.withFlags(nextFlags));
        manager.fireDataChanged();
        success(src, label + " " + (((nextFlags & flag) != 0) ? "enabled" : "disabled")
                + " for waypoint " + group.displayIndexLabel(index));
        return 1;
    }

    private int runMoveWaypointHere(FabricClientCommandSource src, int index) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { error(src, "Not in a world"); return 0; }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        return runMoveWaypointAt(src, index, pos.x(), pos.y(), pos.z());
    }

    private int runMoveWaypointAt(FabricClientCommandSource src, int index, int x, int y, int z) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(group, index, x, y, z);
        manager.fireDataChanged();
        success(src, "Moved waypoint " + group.displayIndexLabel(index)
                + " to " + x + ", " + y + ", " + z);
        return 1;
    }

    private int runRenameWaypoint(FabricClientCommandSource src, int index, String name) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withName(name == null ? "" : name.trim()));
        manager.fireDataChanged();
        success(src, "Renamed waypoint " + group.displayIndexLabel(index));
        return 1;
    }

    private int runSetWaypointColor(FabricClientCommandSource src, int index, String rawColor) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        Integer color = parseRgb(rawColor);
        if (color == null) {
            error(src, "Color must be 6-digit hex, like 58C878.");
            return 0;
        }

        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withColor(color).withFlags(waypoint.flags() | Waypoint.FLAG_LOCKED_COLOR));
        manager.fireDataChanged();
        success(src, "Set waypoint " + group.displayIndexLabel(index)
                + " color to " + formatRgb(color));
        return 1;
    }

    private int runSetWaypointRadius(FabricClientCommandSource src, int index, double radius) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withRadius(radius));
        manager.fireDataChanged();
        success(src, "Set waypoint " + group.displayIndexLabel(index)
                + " radius to " + String.format(Locale.ROOT, "%.1f", radius));
        return 1;
    }

    private int runRenameGroup(FabricClientCommandSource src, int index, String name) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setName(name == null ? "" : name.trim());
        manager.fireDataChanged();
        success(src, "Renamed route [" + index + "] to \"" + group.name() + "\"");
        return 1;
    }

    private int runSetGroupZone(FabricClientCommandSource src, int index, String rawZone) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Zone zone = resolveCommandZone(src, rawZone);
        if (zone == null) return 0;
        group.setZoneId(zone.id());
        manager.fireDataChanged();
        success(src, "Attached route [" + index + "] \"" + group.name()
                + "\" to " + zone.displayName());
        return 1;
    }

    private int runSetGroupMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, "Mode must be static or sequence.");
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] mode to " + mode.name().toLowerCase(Locale.ROOT));
        return 1;
    }

    private int runSetGroupRadius(FabricClientCommandSource src, int index, double radius) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] radius to "
                + String.format(Locale.ROOT, "%.1f", group.defaultRadius()));
        return 1;
    }

    private int runSetGroupSkipAhead(FabricClientCommandSource src, int index, String rawState) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Boolean state = parseToggleState(rawState, group.skipAheadEnabled());
        if (state == null) {
            error(src, "State must be on, off, or toggle.");
            return 0;
        }
        group.setSkipAheadEnabled(state);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] skip ahead "
                + (group.skipAheadEnabled() ? "on" : "off"));
        return 1;
    }

    private int runSetGroupEnabled(FabricClientCommandSource src, int index, boolean enabled) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setEnabled(enabled);
        manager.fireDataChanged();
        success(src, (enabled ? "Enabled" : "Disabled") + " route [" + index + "] \"" + group.name() + "\"");
        return 1;
    }

    private int runSetGroupColorMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.GradientMode mode = parseGradientMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, "Color mode must be one, gradient, or manual.");
            return 0;
        }
        group.setGradientMode(mode);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] color mode to " + rawMode.toLowerCase(Locale.ROOT));
        return 1;
    }

    private int runSetGroupStaticColor(FabricClientCommandSource src, int index, String rawColor) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer color = parseRgb(rawColor);
        if (group == null) return 0;
        if (color == null) {
            error(src, "Color must be 6-digit hex, like 58C878.");
            return 0;
        }
        group.setStaticColor(color);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] color to " + formatRgb(color));
        return 1;
    }

    private int runSetGroupGradient(FabricClientCommandSource src, int index,
                                    String rawStart, String rawEnd) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer start = parseRgb(rawStart);
        Integer end = parseRgb(rawEnd);
        if (group == null) return 0;
        if (start == null || end == null) {
            error(src, "Gradient colors must be 6-digit hex, like 00BFFF FF3040.");
            return 0;
        }
        group.setGradientStartColor(start);
        group.setGradientEndColor(end);
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        manager.fireDataChanged();
        success(src, "Set route [" + index + "] gradient to "
                + formatRgb(start) + " -> " + formatRgb(end));
        return 1;
    }

    private WaypointGroup activeGroupOrError(FabricClientCommandSource src) {
        WaypointGroup visibleGroup = manager.firstActiveGroup();
        if (visibleGroup == null) {
            error(src, "No active route in the current area.");
            return null;
        }
        WaypointGroup editTarget = DungeonRoomRouteSync.durableEditTarget(manager, visibleGroup);
        if (editTarget == null) {
            error(src, "Convert the downloaded dungeon secrets to an editable route first.");
        }
        return editTarget;
    }

    private WaypointGroup groupAtIndexOrError(FabricClientCommandSource src, int index) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, "0-based route index " + index
                    + " out of range (0.." + (all.size() - 1) + ")");
            return null;
        }
        return all.get(index);
    }

    private int resolveActiveWaypointIndex(FabricClientCommandSource src, WaypointGroup group,
                                           Integer requestedIndex) {
        if (group == null) return -1;
        int index = requestedIndex == null ? group.currentIndex() : requestedIndex;
        if (requestedIndex == null && (index < 0 || index >= group.size())) {
            error(src, "No current waypoint in the active route.");
            return -1;
        }
        if (!validateWaypointIndex(src, group, index)) {
            return -1;
        }
        return index;
    }

    private boolean validateWaypointIndex(FabricClientCommandSource src, WaypointGroup group, int index) {
        if (group != null && index >= 0 && index < group.size()) return true;
        int max = group == null ? -1 : group.size() - 1;
        error(src, "0-based waypoint index " + index + " out of range (0.." + max + ")");
        return false;
    }

    private Zone resolveCommandZone(FabricClientCommandSource src, String rawZone) {
        String cleaned = stripQuotes(rawZone == null ? "" : rawZone).trim();
        if (cleaned.isEmpty()) {
            error(src, "Usage: /wp route zone <route> <zone|current>");
            return null;
        }
        if ("current".equalsIgnoreCase(cleaned)) {
            Zone current = manager.currentZone();
            if (current == null) {
                error(src, "No current area is detected.");
                return null;
            }
            return current;
        }
        return cleaned.indexOf(' ') >= 0 || cleaned.indexOf('\'') >= 0
                ? Zone.resolveFromDisplayName(cleaned)
                : Zone.fromId(cleaned);
    }

    private static WaypointGroup.LoadMode parseLoadMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "static", "all" -> WaypointGroup.LoadMode.STATIC;
            case "sequence", "sequenced", "seq" -> WaypointGroup.LoadMode.SEQUENCE;
            default -> null;
        };
    }

    private static WaypointGroup.GradientMode parseGradientMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "one", "static", "solid" -> WaypointGroup.GradientMode.STATIC;
            case "gradient", "auto" -> WaypointGroup.GradientMode.AUTO;
            case "manual" -> WaypointGroup.GradientMode.MANUAL;
            default -> null;
        };
    }

    private static Boolean parseToggleState(String rawState, boolean current) {
        String state = rawState == null ? "toggle" : rawState.trim().toLowerCase(Locale.ROOT);
        return switch (state) {
            case "", "toggle", "flip" -> !current;
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }

    static Integer parseRgb(String rawColor) {
        if (rawColor == null) return null;
        String cleaned = rawColor.trim();
        if (cleaned.startsWith("#")) cleaned = cleaned.substring(1);
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) cleaned = cleaned.substring(2);
        if (cleaned.length() != 6) return null;
        try {
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatRgb(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private int runAdd(FabricClientCommandSource src, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { error(src, "Not in a world"); return 0; }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);

        return runAddAt(src, pos.x(), pos.y(), pos.z(), name);
    }

    private int runAddAt(FabricClientCommandSource src, int x, int y, int z, String name) {
        int index = addPersistentWaypointAt(manager, config, addFlow, x, y, z, name);
        if (index < 0) {
            error(src, "Convert the downloaded dungeon secrets to an editable route first.");
            return 0;
        }
        return 1;
    }

    static int addPersistentWaypointAt(ActiveGroupManager manager, WaypointerConfig config,
                                       WaypointAddFlow addFlow, int x, int y, int z,
                                       String name) {
        if (definitionOnlyRouteRequiresConversion(manager)) return -1;
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        target.add(storedCommandWaypoint(target, config, x, y, z, name));
        int index = target.size() - 1;
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        manager.fireDataChanged();
        return index;
    }

    static int insertPersistentWaypointAt(WaypointGroup target, WaypointerConfig config,
                                          WaypointAddFlow addFlow, int index,
                                          int x, int y, int z, String name) {
        if (target == null || index < 0 || index > target.size()) return -1;
        target.insert(index, storedCommandWaypoint(target, config, x, y, z, name));
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        return index;
    }

    private static Waypoint storedCommandWaypoint(WaypointGroup target, WaypointerConfig config,
                                                  int x, int y, int z, String name) {
        return DungeonRoomWaypointPlacement.toStoredWaypoint(
                target, commandWaypoint(target, config, x, y, z, name));
    }

    private static Waypoint commandWaypoint(WaypointGroup target, WaypointerConfig config,
                                            int x, int y, int z, String name) {
        int flags = target != null && DungeonRoomData.definition(target.zoneId()) != null
                ? DungeonWaypointSkipRules.defaultFlagsAt(x, y, z)
                : 0;
        return new Waypoint(x, y, z, name == null ? "" : name,
                config.defaultWaypointColor(), flags, 0.0);
    }

    private int runAddTempAt(FabricClientCommandSource src, int x, int y, int z, String sourceName) {
        WaypointGroup target = addConfiguredTempWaypoint(x, y, z, sourceName);
        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(target, target.size() - 1);
        }

        success(src, "Added temp waypoint to \"" + target.name()
                + "\" at " + x + ", " + y + ", " + z + " ("
                + defaultTempExpiryDescription() + ")");
        return 1;
    }
    private int runChatTempClick(FabricClientCommandSource src, int x, int y, int z,
                                 String senderArg, String encodedSource) {
        String senderName = "-".equals(senderArg) ? "" : senderArg;
        String sourceName = decodeChatTempSource(encodedSource);

        if (hasShiftDown()) {
            if (senderName.isBlank()) {
                warn(src, "Couldn't identify who sent that waypoint, so nothing was blacklisted.");
            } else {
                boolean nowBlocked = config.toggleChatCoordSenderBlacklist(senderName);
                int removed = nowBlocked ? manager.removeTempWaypointsFromSender(senderName) : 0;
                if (nowBlocked) {
                    success(src, "Blacklisted " + senderName + " for chat waypoints"
                            + (removed > 0 ? " and removed " + removed + " temporary waypoint(s)" : ""));
                } else {
                    success(src, "Removed " + senderName + " from the chat waypoint blacklist");
                }
            }
            return 1;
        }

        ActiveGroupManager.TempWaypointSelection selection = manager.findTempWaypoint(x, y, z, senderName);
        boolean created = false;
        if (selection == null) {
            WaypointGroup target = addConfiguredTempWaypoint(x, y, z, sourceName);
            int index = target.size() - 1;
            selection = new ActiveGroupManager.TempWaypointSelection(target, index);
            created = true;
        }

        if (config.focusTempWaypoints()) {
            manager.focusTempWaypoint(selection.group(), selection.index());
            success(src, "Focused temporary waypoint at " + x + ", " + y + ", " + z);
            return 1;
        }

        success(src, (created ? "Added" : "Temporary waypoint already exists at")
                + " " + x + ", " + y + ", " + z);
        return 1;
    }

    private WaypointGroup addConfiguredTempWaypoint(int x, int y, int z, String sourceName) {
        long now = System.currentTimeMillis();
        return manager.addTempWaypoint(x, y, z, sourceName,
                config.tempDefaultMode(),
                config.defaultTempExpiresAtMillis(now),
                config.defaultWaypointColor());
    }
    private String defaultTempExpiryDescription() {
        return switch (config.tempDefaultMode()) {
            case Waypoint.TEMP_TIME -> "expires after " + config.tempDefaultDurationSec() + " sec";
            case Waypoint.TEMP_UNTIL_REACHED -> "expires when reached";
            case Waypoint.TEMP_UNTIL_LEAVE -> "expires on disconnect";
            default -> "temporary";
        };
    }

    private int runChatCoordBlacklist(FabricClientCommandSource src) {
        List<String> names = config.chatCoordSenderBlacklist();
        if (names.isEmpty()) {
            info(src, "No chat waypoint senders are blacklisted.");
            return 0;
        }
        info(src, Component.literal("Chat waypoint blacklist: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.join(", ", names)).withStyle(ChatFormatting.YELLOW)));
        return names.size();
    }

    private int runChatCoordBlacklistAdd(FabricClientCommandSource src, String senderName) {
        boolean added = config.addChatCoordSenderBlacklist(senderName);
        int removed = added ? manager.removeTempWaypointsFromSender(senderName) : 0;
        if (added) {
            success(src, "Blacklisted " + senderName + " for chat waypoints"
                    + (removed > 0 ? " and removed " + removed + " temporary waypoint(s)" : ""));
        } else {
            info(src, senderName + " is already blacklisted.");
        }
        return added ? 1 : 0;
    }

    private int runChatCoordBlacklistRemove(FabricClientCommandSource src, String senderName) {
        if (config.removeChatCoordSenderBlacklist(senderName)) {
            success(src, "Removed " + senderName + " from the chat waypoint blacklist");
            return 1;
        }
        info(src, senderName + " is not blacklisted.");
        return 0;
    }

    /**
     * Inserts a new waypoint at the configured player-relative position into the
     * active group's waypoint list at {@code index}. {@code index == size} appends, matching
     * the semantics of {@link java.util.List#add(int, Object)} which
     * {@link WaypointGroup#insert(int, Waypoint)} delegates to.
     */
    private int runInsert(FabricClientCommandSource src, int index, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { error(src, "Not in a world"); return 0; }
        if (definitionOnlyRouteRequiresConversion(manager)) {
            error(src, "Convert the downloaded dungeon secrets to an editable route first.");
            return 0;
        }

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            // Mirror the inclusive upper bound from the suggest tooltip so the
            // error message and the completion list agree on what's legal.
            error(src, "0-based insert slot " + index
                    + " out of range (0.." + target.size() + ")");
            return 0;
        }

        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        insertPersistentWaypointAt(target, config, addFlow, index, pos.x(), pos.y(), pos.z(), name);
        manager.fireDataChanged();

        return 1;
    }

    private int runInsertAt(FabricClientCommandSource src, int index,
                            int x, int y, int z, String name) {
        if (definitionOnlyRouteRequiresConversion(manager)) {
            error(src, "Convert the downloaded dungeon secrets to an editable route first.");
            return 0;
        }
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            error(src, "0-based insert slot " + index
                    + " out of range (0.." + target.size() + ")");
            return 0;
        }

        insertPersistentWaypointAt(target, config, addFlow, index, x, y, z, name);
        manager.fireDataChanged();
        return 1;
    }

    private int runRemove(FabricClientCommandSource src, int index) {
        WaypointGroup target = activeGroupOrError(src);
        if (target == null) return 0;
        if (index < 0 || index >= target.size()) {
            error(src, "0-based waypoint index " + index
                    + " out of range (0.." + (target.size() - 1) + ")");
            return 0;
        }
        String displayLabel = target.displayIndexLabel(index);
        Waypoint removed = removeWaypointAt(target, index);
        manager.fireDataChanged();
        success(src, "Removed index " + index + " (#" + displayLabel + ") "
                + removed.x() + ", " + removed.y() + ", " + removed.z());
        return 1;
    }

    static boolean definitionOnlyRouteRequiresConversion(ActiveGroupManager manager) {
        Zone currentZone = manager.currentZone();
        return currentZone != null
                && DungeonRoomRouteSync.secretsRequireConversion(manager, currentZone.id());
    }

    static Waypoint removeWaypointAt(WaypointGroup target, int index) {
        if (target == null || index < 0 || index >= target.size()) return null;
        Waypoint removed = target.get(index);
        target.remove(index);
        return removed;
    }

    private int runClearZone(FabricClientCommandSource src, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (zone == null) { error(src, "No active zone"); return 0; }
        List<WaypointGroup> here = manager.groupsForZone(zone.id());
        if (here.isEmpty()) { info(src, "Nothing to clear in " + zone.displayName()); return 0; }
        if (!confirmed) {
            warn(src, "This will delete " + here.size() + " route(s) in "
                    + zone.displayName() + ". Run /waypointer clear confirm to proceed.");
            return 0;
        }
        int cleared = clearCurrentZoneGroups(manager, true);
        success(src, "Cleared " + here.size() + " route(s) in " + zone.displayName());
        return cleared;
    }

    static int clearCurrentZoneGroups(ActiveGroupManager manager, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (!confirmed || zone == null) return 0;
        List<WaypointGroup> here = List.copyOf(manager.groupsForZone(zone.id()));
        for (WaypointGroup group : here) {
            manager.remove(group.id());
        }
        return here.size();
    }

    private int runExport(FabricClientCommandSource src, WaypointCodec.Options opts) {
        Zone zone = manager.currentZone();
        List<WaypointGroup> toExport = cliExportGroups(manager);
        if (toExport.isEmpty()) { info(src, "Nothing to export" + zoneSuffix()); return 0; }

        String payload = WaypointCodec.encode(toExport, opts);
        boolean copied = setClipboard(payload);

        MutableComponent line = Component.literal("Exported " + toExport.size() + " route(s) (" + payload.length() + " chars)")
                .withStyle(ChatFormatting.GREEN);
        if (!opts.includeNames) line.append(Component.literal(" without names").withStyle(ChatFormatting.GRAY));
        if (copied) line.append(Component.literal(" (copied to clipboard)").withStyle(ChatFormatting.GRAY));
        line.append(Component.literal(" [click to copy]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(payload))));
        src.sendFeedback(WaypointerChatFeedback.suppress(line));
        return toExport.size();
    }

    static List<WaypointGroup> cliExportGroups(ActiveGroupManager manager) {
        Zone zone = manager.currentZone();
        return zone == null ? manager.allGroupsList() : manager.groupsForZone(zone.id());
    }

    private int runImportFromClipboard(FabricClientCommandSource src) {
        String text = getClipboard();
        if (text == null || text.isBlank()) {
            // Hint at the inline form so the user isn't stuck when clipboard access
            // fails (some Linux/X11 configs return nothing) or when they mistyped
            // and expected the payload to be read from args.
            error(src, "Clipboard is empty. Copy the " + WaypointCodec.MAGIC
                    + "... payload first, or run /wp import <payload> to paste it inline.");
            return 0;
        }
        return runImport(src, text, "clipboard");
    }

    /**
     * Read a file from the user's filesystem and import it. Exists alongside
     * {@link #runImportFromClipboard} because coleweight and similar exports are
     * routinely distributed as multi-kilobyte JSON files that exceed Minecraft's
     * 256-char chat line limit and would otherwise require a clipboard round-trip.
     *
     * File size is capped at 8 MiB: more than that is almost certainly a user
     * pointing us at the wrong file, and reading an entire novel-sized blob into
     * a String before failing to parse it would freeze the client.
     */
    private static final long IMPORT_FILE_MAX_BYTES = 8L * 1024 * 1024;

    private int runImportFile(FabricClientCommandSource src, String rawPath) {
        String cleaned = stripQuotes(rawPath).trim();
        if (cleaned.isEmpty()) { error(src, "Usage: /wp importfile <path>"); return 0; }

        Path path;
        try {
            path = Path.of(cleaned);
        } catch (InvalidPathException e) {
            error(src, "Invalid file path: " + e.getReason());
            return 0;
        }
        if (!Files.isRegularFile(path)) {
            error(src, "No readable file at " + path);
            return 0;
        }

        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            error(src, "Couldn't stat " + path + ": " + e.getMessage());
            return 0;
        }
        if (size > IMPORT_FILE_MAX_BYTES) {
            error(src, "File is " + size + " bytes (> " + IMPORT_FILE_MAX_BYTES
                    + "). Refusing to load; check that the path points at a waypoint export.");
            return 0;
        }

        String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            error(src, "Failed to read " + path + ": " + e.getMessage());
            return 0;
        }

        return runImport(src, contents, "file:" + path.getFileName());
    }

    /**
     * Brigadier's greedy-string argument keeps quotes verbatim, but users who copy
     * a Windows path from Explorer's "Copy as path" menu get a quoted path. Strip
     * matched surrounding quotes so both forms work.
     */
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String decodeChatTempSource(String encoded) {
        if (encoded == null || encoded.isBlank() || "-".equals(encoded)) return "";
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean hasShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        var win = mc.getWindow();
        return InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(win, 344 /* GLFW_KEY_RIGHT_SHIFT */);
    }

    private int runImportChat(FabricClientCommandSource src, String handle) {
        String codec = chatImportCache.get(handle);
        if (codec == null) {
            error(src, "That import button has expired. Ask the sender to repost the codec.");
            return 0;
        }
        return runImport(src, codec, "chat");
    }

    private int runImportArgument(FabricClientCommandSource src, String payload) {
        String cached = chatImportCache.get(payload);
        if (cached != null) {
            return runImport(src, cached, "chat");
        }
        return runImport(src, payload, "argument");
    }

        private int runImport(FabricClientCommandSource src, String payload, String origin) {
        try {
            WaypointImporter.ImportResult result = WaypointImporter.importAny(payload);
            // Coleweight (and any JSON source without a zone field) parse into
            // groups tagged with Zone.UNKNOWN. Dropping those into the live
            // manager leaves them in an "unknown" bucket the user has to
            // manually move; instead, snap them to whatever zone the player
            // is currently in so "I'm in the Park, I paste a coleweight
            // route" works as expected. Groups that parsed a real zone are
            // preserved untouched.
            Zone targetZone = manager.currentZone();
            int retargeted = retargetUnknownGroups(result.groups(), targetZone);
            RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);

            manager.addAll(result.groups());
            success(src, "Imported " + result.groups().size() + " route(s) from " + origin
                    + " (format: " + result.source() + ")");
            if (retargeted > 0 && targetZone != null) {
                info(src, retargeted + (retargeted == 1 ? " route" : " routes")
                        + " without zone info assigned to " + targetZone.displayName());
            }
            // Surface the sender's label as a separate gray line so it doesn't
            // visually compete with the success line. The label is sanitized
            // by the codec at decode time, but we still emit it as a literal
            // wrapped in quotes to make any whitespace-only tampering obvious.
            if (!result.label().isEmpty()) {
                info(src, Component.literal("Label: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("\"" + result.label() + "\"")
                                .withStyle(ChatFormatting.WHITE)));
            }

            // Toast + explicit editor link on top of the chat feedback. The chat
            // message is the authoritative record (includes format + retarget
            // details); the toast is a passive glance-indicator so users who
            // ran the command in the middle of gameplay notice without
            // reading chat. Command imports deliberately do not auto-open the
            // editor because that can interrupt gameplay; users can click the
            // link when they want to inspect the imported routes.
            ImportFeedback.success(result.groups(), origin);
            if (!result.groups().isEmpty()) {
                info(src, importEditorHintComponent());
            }
            return result.groups().size();
        } catch (IllegalArgumentException e) {
            error(src, "Import failed: " + e.getMessage());
            ImportFeedback.failure("Invalid import text.");
            return 0;
        }
    }

    static Component importEditorHintComponent() {
        return Component.literal("Open editor to view imported routes ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[Open]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/waypointer gui"))));
    }

    /**
     * Reassign every group tagged {@link Zone#UNKNOWN} to {@code target}'s zone.
     * Returns the count of groups that were actually retargeted so the caller
     * can feed it back to the user. A null target (no current zone) is a
     * no-op: the import still lands in the UNKNOWN bucket, which is the
     * best we can do without a real zone to snap to.
     */
    private static int retargetUnknownGroups(List<WaypointGroup> groups, Zone target) {
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

    /**
     * Default export options reflect the user's persisted config (see Settings).
     * Users can still override per field with {@code /wp export names}, etc.
     *
     * The label is intentionally omitted here: the CLI export path doesn't have a
     * good way to prompt for a label, and silently attaching one from config would
     * surprise users who set it once and forgot. Use {@link com.babbur.waypointer.screen.ExportScreen}
     * (the GUI export panel) to set a label on a per-export basis.
     */
    private WaypointCodec.Options exportOptionsFromConfig() {
        return WaypointCodec.Options.builder()
                .includeNames(config.exportIncludeNames())
                .includeColors(config.exportIncludeColors())
                .includeRadii(config.exportIncludeRadii())
                .includeWaypointFlags(config.exportIncludeWaypointFlags())
                .includeGroupMeta(config.exportIncludeGroupMeta())
                .build();
    }

    private int runCreateGroup(FabricClientCommandSource src, String name) {
        Zone zone = manager.currentZone() == null ? Zone.UNKNOWN : manager.currentZone();
        WaypointGroup g = WaypointGroup.create(name, zone.id(), config.skipAheadMechanicEnabled());
        manager.add(g);
        success(src, "Created route \"" + name + "\" in " + zone.displayName());
        return 1;
    }

    private int runListGroups(FabricClientCommandSource src) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (all.isEmpty()) { info(src, "No routes defined."); return 0; }
        info(src, all.size() + " route(s) total:");
        for (int i = 0; i < all.size(); i++) {
            WaypointGroup g = all.get(i);
            info(src, "  [" + i + "] " + g.name() + " -- zone=" + g.zoneId()
                    + " points=" + g.size() + (g.enabled() ? "" : " (disabled)"));
        }
        return all.size();
    }

    private int runDeleteGroup(FabricClientCommandSource src, int index, boolean confirmed) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, "0-based route index " + index
                    + " out of range (0.." + (all.size() - 1) + ")");
            return 0;
        }
        WaypointGroup g = all.get(index);
        if (!confirmed) {
            warn(src, "This will delete route [" + index + "] \"" + g.name()
                    + "\" with " + g.size()
                    + " waypoint(s). Run /waypointer route delete " + index
                    + " confirm to proceed.");
            return 0;
        }
        manager.remove(g.id());
        success(src, "Deleted route \"" + g.name() + "\"");
        return 1;
    }

    // --- helpers ------------------------------------------------------------------------------

    private String zoneSuffix() {
        Zone zone = manager.currentZone();
        return zone == null ? "" : " (" + zone.displayName() + ")";
    }

    // Clipboard writes keep the AWT path because they can be triggered from command
    // callbacks. Reads prefer Minecraft's clipboard so /wp import matches the GUI path.
    private static boolean setClipboard(String text) {
        try {
            Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
            c.setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            Waypointer.LOGGER.warn("Clipboard write failed", t);
            return false;
        }
    }

    private static String getClipboard() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                String text = mc.keyboardHandler.getClipboard();
                if (text != null && !text.isBlank()) return text;
            }
        } catch (Throwable t) {
            Waypointer.LOGGER.warn("Minecraft clipboard read failed", t);
        }

        try {
            Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = c.getData(DataFlavor.stringFlavor);
            return data == null ? null : data.toString();
        } catch (Throwable t) {
            Waypointer.LOGGER.warn("Clipboard read failed", t);
            return null;
        }
    }

    private static void info(FabricClientCommandSource src, String msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                Component.literal(msg).withStyle(ChatFormatting.GRAY)));
    }

    private static void info(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(msg));
    }

    private static void success(FabricClientCommandSource src, String msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                Component.literal(msg).withStyle(ChatFormatting.GREEN)));
    }

    private static void warn(FabricClientCommandSource src, String msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                Component.literal(msg).withStyle(ChatFormatting.YELLOW)));
    }

    private static void error(FabricClientCommandSource src, String msg) {
        src.sendError(WaypointerChatFeedback.suppress(
                Component.literal(msg).withStyle(ChatFormatting.RED)));
    }
}
