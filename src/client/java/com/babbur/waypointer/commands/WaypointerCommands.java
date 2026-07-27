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
                info(ctx.getSource(), Component.translatable(
                        "waypointer.command.happy_snowman.enabled"));
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
                .then(literal("unskip").executes(ctx -> runUnskipCurrentWaypoint(ctx.getSource())))
                .then(literal("skipto")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestSkipTargets())
                                .executes(ctx -> runSkipTo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target")))))
                .then(currentSubwayCommand("sub"))
                .then(currentFlagCommand("tiny", Waypoint.FLAG_SMALL_SUBWAYPOINT,
                        "waypointer.command.waypoint.flag.tiny", true))
                .then(currentFlagCommand("filled", Waypoint.FLAG_FILLED_SUBWAYPOINT,
                        "waypointer.command.waypoint.flag.filled", true))
                .then(currentFlagCommand("hap", Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                        "waypointer.command.waypoint.flag.hide_after_parent", true))
                .then(currentFlagCommand("sts", Waypoint.FLAG_SKIP_ON_STAND,
                        "waypointer.command.waypoint.flag.stand_to_skip", false))
                .then(currentFlagCommand("its", Waypoint.FLAG_SKIP_ON_INTERACT,
                        "waypointer.command.waypoint.flag.interact_to_skip", false))
                .then(currentFlagCommand("los", Waypoint.FLAG_DEPTH_CHECKED,
                        "waypointer.command.waypoint.flag.line_of_sight", false))
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
                                                                                String labelKey,
                                                                                boolean subwaypointOnly) {
        return literal(name)
                .executes(ctx -> runToggleWaypointFlag(
                        ctx.getSource(), null, flag, labelKey, subwaypointOnly))
                .then(argument("index", IntegerArgumentType.integer(0))
                        .suggests(suggestActiveGroupIndices())
                        .executes(ctx -> runToggleWaypointFlag(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"),
                                flag, labelKey, subwaypointOnly)));
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
                    builder.suggest(h, Component.translatable(
                            "waypointer.command.suggestion.cached_import"));
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
                builder.suggest(value, playerCoordSuggestionLabel(axis));
            }
            return builder.buildFuture();
        };
    }

    private Component playerCoordSuggestionLabel(Axis axis) {
        if (axis == Axis.Y && config.placeNewWaypointsBelowPlayer()) {
            return Component.translatable(
                    "waypointer.command.suggestion.player_y_below");
        }
        return Component.translatable(
                "waypointer.command.suggestion.player_axis",
                axis.name().toLowerCase(Locale.ROOT));
    }

    private SuggestionProvider<FabricClientCommandSource> suggestImportPayloads() {
        return (ctx, builder) -> {
            for (String handle : chatImportCache.handles()) {
                if (handle.toLowerCase(Locale.ROOT)
                        .startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(handle, Component.translatable(
                            "waypointer.command.suggestion.cached_chat_import"));
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestImportFiles() {
        return (ctx, builder) -> {
            String file = storage.file().toString();
            if (file.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(file, Component.translatable(
                        "waypointer.command.suggestion.storage_file"));
            }
            Path parent = storage.file().getParent();
            if (parent != null
                    && parent.toString().toLowerCase(Locale.ROOT)
                            .startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(parent.toString(), Component.translatable(
                        "waypointer.command.suggestion.config_folder"));
            }
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
            error(src, Component.translatable(
                    "waypointer.command.devmode.unavailable"));
            return 0;
        }
        boolean enable = requestedState == null ? !monitor.enabled() : requestedState;
        try {
            Path file = enable ? monitor.enable() : monitor.disable();
            if (enable) {
                success(src, Component.translatable(
                        "waypointer.command.devmode.enabled", file));
            } else {
                success(src, Component.translatable(
                        "waypointer.command.devmode.disabled",
                        file == null
                                ? Component.translatable("waypointer.common.none")
                                : file));
            }
            return 1;
        } catch (RuntimeException e) {
            Waypointer.LOGGER.error("Could not change developer mode", e);
            error(src, Component.translatable(
                    "waypointer.command.devmode.change_failed"));
            return 0;
        }
    }

    private int runDeveloperModeStatus(FabricClientCommandSource src) {
        var monitor = WaypointerClient.developerModeMonitor();
        if (monitor == null) {
            error(src, Component.translatable(
                    "waypointer.command.devmode.unavailable"));
            return 0;
        }
        info(src, monitor.statusComponent());
        return 1;
    }

    private int runDeveloperModeReport(FabricClientCommandSource src) {
        var monitor = WaypointerClient.developerModeMonitor();
        if (monitor == null || !monitor.enabled()) {
            warn(src, Component.translatable(
                    "waypointer.command.devmode.off"));
            return 0;
        }
        monitor.writeReport("manual command");
        success(src, Component.translatable(
                "waypointer.command.devmode.report_written", monitor.logFile()));
        return 1;
    }

    private int runToggleEditModeCommand(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        if (manager == null || config == null) {
            error(src, Component.translatable(
                    "waypointer.input.edit_mode.unavailable"));
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
            new HelpSection("basics", "waypointer.command.help.section.basics",
                    List.of(
                            new HelpRow("", "waypointer.command.help.open_editor", "", "gui"),
                            new HelpRow(" gui", "waypointer.command.help.open_editor", "gui"),
                            new HelpRow(" list", "waypointer.command.help.list", "list"),
                            new HelpRow(" help [all|section|command]", "waypointer.command.help.help",
                                    "help", "help subway"))),
            new HelpSection("route", "waypointer.command.help.section.route",
                    List.of(
                            new HelpRow(" add [name]", "waypointer.command.help.add",
                                    "add", "add Fairy Soul"),
                            new HelpRow(" add at <x> <y> <z> [name]", "waypointer.command.help.add_at",
                                    "add at 12 70 -4", "add at 12 70 -4 Lever"),
                            new HelpRow(" insert <slot> [name]", "waypointer.command.help.insert",
                                    "insert 2", "insert 2 Secret"),
                            new HelpRow(" insert <slot> at <x> <y> <z> [name]", "waypointer.command.help.insert_at",
                                    "insert 2 at 12 70 -4", "insert 2 at 12 70 -4 Chest"),
                            new HelpRow(" remove <index>", "waypointer.command.help.remove",
                                    "remove 3"),
                            new HelpRow(" move <index> <slot>", "waypointer.command.help.move",
                                    "move 4 2"),
                            new HelpRow(" skipto <n[.sub]>", "waypointer.command.help.skipto",
                                    "skipto 3", "skipto 3.2"),
                            new HelpRow(" skip", "waypointer.command.help.skip",
                                    "skip"),
                            new HelpRow(" unskip", "waypointer.command.help.unskip",
                                    "unskip"),
                            new HelpRow(" reset", "waypointer.command.help.reset",
                                    "reset"),
                            new HelpRow(" mode <static|sequence>", "waypointer.command.help.mode",
                                    "mode sequence", "mode static"),
                            new HelpRow(" radius <blocks>", "waypointer.command.help.radius",
                                    "radius 4.5"),
                            new HelpRow(" editmode", "waypointer.command.help.edit_mode",
                                    "editmode"),
                            new HelpRow(" edit mode", "waypointer.command.help.edit_mode",
                                    "edit mode"),
                            new HelpRow(" clear [confirm]", "waypointer.command.help.clear",
                                    "clear", "clear confirm"))),
            new HelpSection("subway", "waypointer.command.help.section.subway",
                    List.of(
                            new HelpRow(" sub [index]", "waypointer.command.help.sub",
                                    "sub", "sub 4"),
                            new HelpRow(" tiny [index]", "waypointer.command.help.tiny",
                                    "tiny", "tiny 4"),
                            new HelpRow(" filled [index]", "waypointer.command.help.filled",
                                    "filled", "filled 4"),
                            new HelpRow(" hap [index]", "waypointer.command.help.hap",
                                    "hap", "hap 4"),
                            new HelpRow(" sts [index]", "waypointer.command.help.sts",
                                    "sts", "sts 4"),
                            new HelpRow(" its [index]", "waypointer.command.help.its",
                                    "its", "its 4"),
                            new HelpRow(" los [index]", "waypointer.command.help.los",
                                    "los", "los 4"))),
            new HelpSection("waypoint", "waypointer.command.help.section.waypoint",
                    List.of(
                            new HelpRow(" waypoint move <index> here", "waypointer.command.help.waypoint_move_here",
                                    "waypoint move 3 here"),
                            new HelpRow(" waypoint move <index> at <x> <y> <z>", "waypointer.command.help.waypoint_move_at",
                                    "waypoint move 3 at 12 70 -4"),
                            new HelpRow(" waypoint rename <index> <name>", "waypointer.command.help.waypoint_rename",
                                    "waypoint rename 3 Fairy Soul"),
                            new HelpRow(" waypoint color <index> <hex>", "waypointer.command.help.waypoint_color",
                                    "waypoint color 3 58C878"),
                            new HelpRow(" waypoint radius <index> <blocks>", "waypointer.command.help.waypoint_radius",
                                    "waypoint radius 3 1.5"),
                            new HelpRow(" waypoint sub <index>", "waypointer.command.help.waypoint_sub",
                                    "waypoint sub 4"))),
            new HelpSection("routes", "waypointer.command.help.section.routes",
                    List.of(
                            new HelpRow(" route create <name>", "waypointer.command.help.route_create",
                                    "route create Foraging Route"),
                            new HelpRow(" route list", "waypointer.command.help.route_list",
                                    "route list"),
                            new HelpRow(" route rename <index> <name>", "waypointer.command.help.route_rename",
                                    "route rename 1 Park Route"),
                            new HelpRow(" route zone <index> <zone|current>", "waypointer.command.help.route_zone",
                                    "route zone 1 current", "route zone 1 the_park"),
                            new HelpRow(" route area <index> <zone|current>", "waypointer.command.help.route_area",
                                    "route area 1 current"),
                            new HelpRow(" area <route> <zone|current>", "waypointer.command.help.area",
                                    "area 1 current"),
                            new HelpRow(" route mode <index> <static|sequence>", "waypointer.command.help.route_mode",
                                    "route mode 1 static"),
                            new HelpRow(" route radius <index> <blocks>", "waypointer.command.help.route_radius",
                                    "route radius 1 4.5"),
                            new HelpRow(" route skipahead <index> [on|off|toggle]", "waypointer.command.help.route_skipahead",
                                    "route skipahead 1 off"),
                            new HelpRow(" route enable <index>", "waypointer.command.help.route_enable",
                                    "route enable 1"),
                            new HelpRow(" route disable <index>", "waypointer.command.help.route_disable",
                                    "route disable 1"),
                            new HelpRow(" route colormode <index> <one|gradient|manual>", "waypointer.command.help.route_colormode",
                                    "route colormode 1 gradient"),
                            new HelpRow(" route color <index> <hex>", "waypointer.command.help.route_color",
                                    "route color 1 4FE05A"),
                            new HelpRow(" route gradient <index> <start> <end>", "waypointer.command.help.route_gradient",
                                    "route gradient 1 00BFFF FF3040"),
                            new HelpRow(" route delete <index> [confirm]", "waypointer.command.help.route_delete",
                                    "route delete 1", "route delete 1 confirm"),
                            new HelpRow(" group ...", "waypointer.command.help.group_alias",
                                    "group list"))),
            new HelpSection("sharing", "waypointer.command.help.section.sharing",
                    List.of(
                            new HelpRow(" export [names|nonames]", "waypointer.command.help.export",
                                    "export", "export names"),
                            new HelpRow(" import [payload]", "waypointer.command.help.import",
                                    "import", "import WP:..."),
                            new HelpRow(" importfile <path>", "waypointer.command.help.importfile",
                                    "importfile C:\\routes\\waypoints.json"),
                            new HelpRow(" importchat <handle>", "waypointer.command.help.importchat",
                                    "importchat A1b2"))),
            new HelpSection("chat", "waypointer.command.help.section.chat",
                    List.of(
                            new HelpRow(" addtemp at <x> <y> <z> [source]", "waypointer.command.help.addtemp",
                                    "addtemp at 12 70 -4 Party"),
                            new HelpRow(" chattemp <x> <y> <z> <sender> <source>", "waypointer.command.help.chattemp",
                                    "chattemp 12 70 -4 Babbur party"),
                            new HelpRow(" blacklist", "waypointer.command.help.blacklist",
                                    "blacklist"),
                            new HelpRow(" blacklist add <name>", "waypointer.command.help.blacklist_add",
                                    "blacklist add Babbur"),
                            new HelpRow(" blacklist remove <name>", "waypointer.command.help.blacklist_remove",
                                    "blacklist remove Babbur"))),
            new HelpSection("debug", "waypointer.command.help.section.debug",
                    List.of(
                            new HelpRow(" debug", "waypointer.command.help.debug",
                                    "debug"),
                            new HelpRow(" devmode [on|off|status|report]",
                                    "waypointer.command.help.devmode",
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
            info(src, Component.translatable("waypointer.command.help.title")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.translatable("waypointer.command.help.hover_hint")
                            .withStyle(ChatFormatting.DARK_GRAY)));
            for (HelpSection section : HELP_SECTIONS) {
                renderHelpSection(src, prefix, section);
            }
            renderHelpFooter(src, root, -1);
            return 1;
        }

        int pageIdx = resolveHelpPage(target);
        if (pageIdx < 0) {
            error(src, Component.translatable(
                    "waypointer.command.help.unknown", target, "/" + root + " help"));
            return 0;
        }

        HelpSection section = HELP_SECTIONS.get(pageIdx);

        info(src, Component.translatable(
                        "waypointer.command.help.page_title",
                        Component.translatable(section.titleKey()))
                .withStyle(ChatFormatting.AQUA));

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
            String localizedTitle = Component.translatable(s.titleKey()).getString();
            if (s.id().equals(t)
                    || (!localizedTitle.equals(s.titleKey())
                            && localizedTitle.toLowerCase(Locale.ROOT).startsWith(t))) {
                return i;
            }
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
                Component.translatable(section.titleKey()).withStyle(ChatFormatting.YELLOW)));
        for (HelpRow row : section.rows()) {
            helpLine(src, prefix, row);
        }
    }

    private void renderHelpFooter(FabricClientCommandSource src, String root, int pageIdx) {
        MutableComponent footer = Component.empty();

        footer.append(Component.translatable("waypointer.command.help.sections")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (int i = 0; i < HELP_SECTIONS.size(); i++) {
            HelpSection s = HELP_SECTIONS.get(i);
            boolean current = i == pageIdx;
            MutableComponent jump = Component.literal(s.id());
            jump.withStyle(current
                    ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                    : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/" + root + " help " + s.id()))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                    Component.translatable(s.titleKey()))));
            footer.append(jump);
            footer.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        }

        MutableComponent all = Component.translatable("waypointer.command.help.all");
        all.withStyle(pageIdx < 0
                ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + root + " help all"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.translatable("waypointer.command.help.show_all"))));
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
            builder.suggest("all", Component.translatable("waypointer.command.help.show_all"));
            for (int i = 0; i < HELP_SECTIONS.size(); i++) {
                String n = Integer.toString(i + 1);
                if (n.startsWith(prefix)) {
                    builder.suggest(n, Component.translatable(
                            HELP_SECTIONS.get(i).titleKey()));
                }
            }
            for (HelpSection s : HELP_SECTIONS) {
                if (s.id().startsWith(prefix)) {
                    builder.suggest(s.id(), Component.translatable(s.titleKey()));
                }
            }
            Set<String> commandWords = new LinkedHashSet<>();
            for (HelpSection s : HELP_SECTIONS) {
                for (HelpRow row : s.rows()) {
                    String commandWord = helpCommandWord(row.usage());
                    if (!commandWord.isEmpty() && commandWords.add(commandWord)
                            && commandWord.startsWith(prefix)) {
                        builder.suggest(commandWord, Component.translatable(s.titleKey()));
                    }
                }
            }
            builder.suggest("all", Component.translatable("waypointer.command.help.show_all"));
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
        hover.append(Component.translatable(row.descriptionKey())
                .withStyle(ChatFormatting.YELLOW));
        hover.append(Component.translatable("waypointer.command.help.usage")
                .withStyle(ChatFormatting.AQUA));
        hover.append(highlightedCommand(prefix, row.usage()));
        if (!row.examples().isEmpty()) {
            hover.append(Component.translatable("waypointer.command.help.examples")
                    .withStyle(ChatFormatting.GREEN));
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
    private record HelpRow(String usage, String descriptionKey, List<String> examples) {
        HelpRow(String usage, String descriptionKey, String... examples) {
            this(usage, descriptionKey, List.of(examples));
        }
    }

    /**
     * One page of help. {@code id} is the short lookup key ({@code "groups"}),
     * {@code title} is the on-screen heading ({@code "Groups & debug"}).
     * Kept separate so title changes don't invalidate users' muscle memory
     * for the jump commands.
     */
    private record HelpSection(String id, String titleKey, List<HelpRow> rows) {}

    private int runList(FabricClientCommandSource src) {
        List<WaypointGroup> active = manager.activeGroups();
        if (active.isEmpty()) {
            Zone zone = manager.currentZone();
            info(src, zone == null
                    ? Component.translatable("waypointer.command.list.empty")
                    : Component.translatable(
                            "waypointer.command.list.empty_in_zone", zone.displayName()));
            return 0;
        }
        for (WaypointGroup g : active) {
            String currentIndexText = Integer.toString(g.currentIndex());
            if (g.currentIndex() >= 0 && g.currentIndex() < g.size()) {
                currentIndexText += " (#" + g.displayIndexLabel(g.currentIndex()) + ")";
            }
            info(src, Component.translatable(
                    "waypointer.command.list.route",
                    Component.literal(g.name()).withStyle(ChatFormatting.AQUA),
                    g.size(), currentIndexText));
            int shown = Math.min(g.size(), 16);
            for (int i = 0; i < shown; i++) {
                Waypoint w = g.get(i);
                ChatFormatting color = i < g.currentIndex() ? ChatFormatting.DARK_GRAY
                        : i == g.currentIndex() ? ChatFormatting.YELLOW
                        : ChatFormatting.WHITE;
                info(src, Component.translatable(
                        "waypointer.command.list.waypoint",
                        g.displayIndexLabel(i), i,
                        Component.literal(w.x() + ", " + w.y() + ", " + w.z())
                                .withStyle(color),
                        w.hasName()
                                ? Component.literal(w.name()).withStyle(ChatFormatting.GRAY)
                                : Component.empty()));
            }
            if (g.size() > shown) {
                info(src, Component.translatable(
                        "waypointer.command.list.more", g.size() - shown));
            }
        }
        return active.size();
    }

    private int runSkipCurrentWaypoint(FabricClientCommandSource src) {
        int moved = WaypointerKeybinds.skipCurrentWaypointTargets(
                manager, config, System.currentTimeMillis());
        if (moved == 0) {
            error(src, Component.translatable("waypointer.command.skip.no_route"));
            return 0;
        }
        success(src, Component.translatable(
                moved == 1
                        ? "waypointer.command.skip.success.one"
                        : "waypointer.command.skip.success.many",
                moved));
        return moved;
    }

    private int runUnskipCurrentWaypoint(FabricClientCommandSource src) {
        int moved = WaypointerKeybinds.unskipCurrentWaypointTargets(manager);
        if (moved == 0) {
            error(src, Component.translatable(
                    "waypointer.command.unskip.no_route"));
            return 0;
        }
        success(src, Component.translatable(
                moved == 1
                        ? "waypointer.command.unskip.success.one"
                        : "waypointer.command.unskip.success.many",
                moved));
        return moved;
    }

    private int runSkipTo(FabricClientCommandSource src, String target) {
        List<WaypointGroup> activeGroups = manager.activeGroups();
        if (activeGroups.isEmpty()) {
            error(src, Component.translatable("waypointer.command.skip.no_route"));
            return 0;
        }
        SkipToOutcome outcome = skipActiveGroupsToTarget(activeGroups, target);
        if (outcome.moved() == 0) {
            error(src, outcome.error() == null
                    ? Component.translatable(
                            "waypointer.command.skipto.no_target", target)
                    : Component.literal(outcome.error()));
            return 0;
        }
        manager.fireDataChanged();

        success(src, Component.translatable(
                outcome.moved() == 1
                        ? "waypointer.command.skipto.success.one"
                        : "waypointer.command.skipto.success.many",
                outcome.moved(), outcome.firstMovedLabel()));
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
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_route"));
            return 0;
        }
        group.resetProgress();
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.reset.success", group.name()));
        return 1;
    }

    private int runRemoveRouteRecord(FabricClientCommandSource src,
                                     String encodedGroupId,
                                     long expectedTimeMillis) {
        String groupId;
        try {
            groupId = new String(Base64.getUrlDecoder().decode(encodedGroupId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidId) {
            error(src, Component.translatable(
                    "waypointer.command.record_link.invalid"));
            return 0;
        }

        WaypointGroup group = manager.get(groupId);
        if (group == null || !group.removeBestTimeMillis(expectedTimeMillis)) {
            error(src, Component.translatable(
                    "waypointer.command.record_link.stale"));
            return 0;
        }

        manager.fireDataChangedFor(group);
        success(src, Component.translatable(
                "waypointer.command.record_link.removed", group.name()));
        return 1;
    }

    private int runSetActiveGroupMode(FabricClientCommandSource src, String rawMode) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (mode == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.mode"));
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.mode_set",
                group.name(), mode.name().toLowerCase(Locale.ROOT)));
        return 1;
    }

    private int runSetActiveGroupRadius(FabricClientCommandSource src, double radius) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.radius_set",
                group.name(), String.format(Locale.ROOT, "%.1f", group.defaultRadius())));
        return 1;
    }

    private int runMoveWaypointToSlot(FabricClientCommandSource src, int index, int slot) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        if (slot < 0 || slot >= group.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.move_slot_range",
                    slot, group.size() - 1));
            return 0;
        }
        group.move(index, slot);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.reordered", index, slot));
        return 1;
    }

    private int runToggleSubwaypoint(FabricClientCommandSource src, Integer requestedIndex) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;

        boolean wasSubwaypoint = group.isSubwaypoint(index);
        if (!group.toggleSubwaypoint(index)) {
            error(src, index == 0
                    ? Component.translatable(
                            "waypointer.command.waypoint.first_not_subwaypoint")
                    : Component.translatable(
                            "waypointer.command.waypoint.not_subwaypoint", index));
            return 0;
        }
        if (!wasSubwaypoint && group.isSubwaypoint(index)) {
            group.setSkipAheadEnabled(false);
        }
        manager.fireDataChanged();
        success(src, Component.translatable(
                group.isSubwaypoint(index)
                        ? "waypointer.command.waypoint.subwaypoint"
                        : "waypointer.command.waypoint.main",
                group.displayIndexLabel(index)));
        return 1;
    }

    private int runToggleWaypointFlag(FabricClientCommandSource src, Integer requestedIndex,
                                      int flag, String labelKey, boolean subwaypointOnly) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;
        if (subwaypointOnly && !group.isSubwaypoint(index)) {
            error(src, Component.translatable(
                    "waypointer.command.waypoint.flag_requires_subwaypoint",
                    group.displayIndexLabel(index), index));
            return 0;
        }

        Waypoint waypoint = group.get(index);
        int nextFlags = waypoint.flags() ^ flag;
        group.set(index, waypoint.withFlags(nextFlags));
        manager.fireDataChanged();
        success(src, Component.translatable(
                ((nextFlags & flag) != 0)
                        ? "waypointer.command.waypoint.flag_enabled"
                        : "waypointer.command.waypoint.flag_disabled",
                Component.translatable(labelKey), group.displayIndexLabel(index)));
        return 1;
    }

    private int runMoveWaypointHere(FabricClientCommandSource src, int index) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        return runMoveWaypointAt(src, index, pos.x(), pos.y(), pos.z());
    }

    private int runMoveWaypointAt(FabricClientCommandSource src, int index, int x, int y, int z) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(group, index, x, y, z);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.moved",
                group.displayIndexLabel(index), x, y, z));
        return 1;
    }

    private int runRenameWaypoint(FabricClientCommandSource src, int index, String name) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withName(name == null ? "" : name.trim()));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.renamed",
                group.displayIndexLabel(index)));
        return 1;
    }

    private int runSetWaypointColor(FabricClientCommandSource src, int index, String rawColor) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        Integer color = parseRgb(rawColor);
        if (color == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_hex"));
            return 0;
        }

        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withColor(color).withFlags(waypoint.flags() | Waypoint.FLAG_LOCKED_COLOR));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.color_set",
                group.displayIndexLabel(index), formatRgb(color)));
        return 1;
    }

    private int runSetWaypointRadius(FabricClientCommandSource src, int index, double radius) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withRadius(radius));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.radius_set",
                group.displayIndexLabel(index),
                String.format(Locale.ROOT, "%.1f", radius)));
        return 1;
    }

    private int runRenameGroup(FabricClientCommandSource src, int index, String name) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setName(name == null ? "" : name.trim());
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.renamed", index, group.name()));
        return 1;
    }

    private int runSetGroupZone(FabricClientCommandSource src, int index, String rawZone) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Zone zone = resolveCommandZone(src, rawZone);
        if (zone == null) return 0;
        group.setZoneId(zone.id());
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.zone_set",
                index, group.name(), zone.displayName()));
        return 1;
    }

    private int runSetGroupMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, Component.translatable("waypointer.command.error.mode"));
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.indexed_mode_set",
                index, mode.name().toLowerCase(Locale.ROOT)));
        return 1;
    }

    private int runSetGroupRadius(FabricClientCommandSource src, int index, double radius) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.indexed_radius_set",
                index, String.format(Locale.ROOT, "%.1f", group.defaultRadius())));
        return 1;
    }

    private int runSetGroupSkipAhead(FabricClientCommandSource src, int index, String rawState) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Boolean state = parseToggleState(rawState, group.skipAheadEnabled());
        if (state == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.toggle_state"));
            return 0;
        }
        group.setSkipAheadEnabled(state);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.skip_ahead_set",
                index,
                Component.translatable(group.skipAheadEnabled()
                        ? "waypointer.common.on"
                        : "waypointer.common.off")));
        return 1;
    }

    private int runSetGroupEnabled(FabricClientCommandSource src, int index, boolean enabled) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setEnabled(enabled);
        manager.fireDataChanged();
        success(src, Component.translatable(
                enabled
                        ? "waypointer.command.route.enabled"
                        : "waypointer.command.route.disabled",
                index, group.name()));
        return 1;
    }

    private int runSetGroupColorMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.GradientMode mode = parseGradientMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_mode"));
            return 0;
        }
        group.setGradientMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.color_mode_set",
                index, rawMode.toLowerCase(Locale.ROOT)));
        return 1;
    }

    private int runSetGroupStaticColor(FabricClientCommandSource src, int index, String rawColor) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer color = parseRgb(rawColor);
        if (group == null) return 0;
        if (color == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_hex"));
            return 0;
        }
        group.setStaticColor(color);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.color_set", index, formatRgb(color)));
        return 1;
    }

    private int runSetGroupGradient(FabricClientCommandSource src, int index,
                                    String rawStart, String rawEnd) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer start = parseRgb(rawStart);
        Integer end = parseRgb(rawEnd);
        if (group == null) return 0;
        if (start == null || end == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.gradient_hex"));
            return 0;
        }
        group.setGradientStartColor(start);
        group.setGradientEndColor(end);
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.gradient_set",
                index, formatRgb(start), formatRgb(end)));
        return 1;
    }

    private WaypointGroup activeGroupOrError(FabricClientCommandSource src) {
        WaypointGroup visibleGroup = manager.firstActiveGroup();
        if (visibleGroup == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_route"));
            return null;
        }
        WaypointGroup editTarget = DungeonRoomRouteSync.durableEditTarget(manager, visibleGroup);
        if (editTarget == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
        }
        return editTarget;
    }

    private WaypointGroup groupAtIndexOrError(FabricClientCommandSource src, int index) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_index_range",
                    index, all.size() - 1));
            return null;
        }
        return all.get(index);
    }

    private int resolveActiveWaypointIndex(FabricClientCommandSource src, WaypointGroup group,
                                           Integer requestedIndex) {
        if (group == null) return -1;
        int index = requestedIndex == null ? group.currentIndex() : requestedIndex;
        if (requestedIndex == null && (index < 0 || index >= group.size())) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_current_waypoint"));
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
        error(src, Component.translatable(
                "waypointer.command.error.waypoint_index_range", index, max));
        return false;
    }

    private Zone resolveCommandZone(FabricClientCommandSource src, String rawZone) {
        String cleaned = stripQuotes(rawZone == null ? "" : rawZone).trim();
        if (cleaned.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_zone_usage"));
            return null;
        }
        if ("current".equalsIgnoreCase(cleaned)) {
            Zone current = manager.currentZone();
            if (current == null) {
                error(src, Component.translatable(
                        "waypointer.command.error.no_current_area"));
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
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);

        return runAddAt(src, pos.x(), pos.y(), pos.z(), name);
    }

    private int runAddAt(FabricClientCommandSource src, int x, int y, int z, String name) {
        int index = addPersistentWaypointAt(manager, config, addFlow, x, y, z, name);
        if (index < 0) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
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

        success(src, Component.translatable(
                "waypointer.command.temp.added",
                target.name(), x, y, z, defaultTempExpiryDescription()));
        return 1;
    }
    private int runChatTempClick(FabricClientCommandSource src, int x, int y, int z,
                                 String senderArg, String encodedSource) {
        String senderName = "-".equals(senderArg) ? "" : senderArg;
        String sourceName = decodeChatTempSource(encodedSource);

        if (hasShiftDown()) {
            if (senderName.isBlank()) {
                warn(src, Component.translatable(
                        "waypointer.command.blacklist.unknown_sender"));
            } else {
                boolean nowBlocked = config.toggleChatCoordSenderBlacklist(senderName);
                int removed = nowBlocked ? manager.removeTempWaypointsFromSender(senderName) : 0;
                if (nowBlocked) {
                    success(src, removed > 0
                            ? Component.translatable(
                                    "waypointer.command.blacklist.added_and_removed",
                                    senderName, removed)
                            : Component.translatable(
                                    "waypointer.command.blacklist.added", senderName));
                } else {
                    success(src, Component.translatable(
                            "waypointer.command.blacklist.removed", senderName));
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
            success(src, Component.translatable(
                    "waypointer.command.temp.focused", x, y, z));
            return 1;
        }

        success(src, Component.translatable(
                created
                        ? "waypointer.command.temp.created"
                        : "waypointer.command.temp.exists",
                x, y, z));
        return 1;
    }

    private WaypointGroup addConfiguredTempWaypoint(int x, int y, int z, String sourceName) {
        long now = System.currentTimeMillis();
        return manager.addTempWaypoint(x, y, z, sourceName,
                config.tempDefaultMode(),
                config.defaultTempExpiresAtMillis(now),
                config.defaultWaypointColor());
    }
    private Component defaultTempExpiryDescription() {
        return switch (config.tempDefaultMode()) {
            case Waypoint.TEMP_TIME -> Component.translatable(
                    "waypointer.command.temp.expiry.timed",
                    config.tempDefaultDurationSec());
            case Waypoint.TEMP_UNTIL_REACHED -> Component.translatable(
                    "waypointer.command.temp.expiry.reached");
            case Waypoint.TEMP_UNTIL_LEAVE -> Component.translatable(
                    "waypointer.command.temp.expiry.disconnect");
            default -> Component.translatable(
                    "waypointer.command.temp.expiry.temporary");
        };
    }

    private int runChatCoordBlacklist(FabricClientCommandSource src) {
        List<String> names = config.chatCoordSenderBlacklist();
        if (names.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.blacklist.empty"));
            return 0;
        }
        info(src, Component.translatable(
                "waypointer.command.blacklist.list",
                Component.literal(String.join(", ", names))
                        .withStyle(ChatFormatting.YELLOW)));
        return names.size();
    }

    private int runChatCoordBlacklistAdd(FabricClientCommandSource src, String senderName) {
        boolean added = config.addChatCoordSenderBlacklist(senderName);
        int removed = added ? manager.removeTempWaypointsFromSender(senderName) : 0;
        if (added) {
            success(src, removed > 0
                    ? Component.translatable(
                            "waypointer.command.blacklist.added_and_removed",
                            senderName, removed)
                    : Component.translatable(
                            "waypointer.command.blacklist.added", senderName));
        } else {
            info(src, Component.translatable(
                    "waypointer.command.blacklist.already", senderName));
        }
        return added ? 1 : 0;
    }

    private int runChatCoordBlacklistRemove(FabricClientCommandSource src, String senderName) {
        if (config.removeChatCoordSenderBlacklist(senderName)) {
            success(src, Component.translatable(
                    "waypointer.command.blacklist.removed", senderName));
            return 1;
        }
        info(src, Component.translatable(
                "waypointer.command.blacklist.not_listed", senderName));
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
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        if (definitionOnlyRouteRequiresConversion(manager)) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
            return 0;
        }

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            // Mirror the inclusive upper bound from the suggest tooltip so the
            // error message and the completion list agree on what's legal.
            error(src, Component.translatable(
                    "waypointer.command.error.insert_slot_range",
                    index, target.size()));
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
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
            return 0;
        }
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.insert_slot_range",
                    index, target.size()));
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
            error(src, Component.translatable(
                    "waypointer.command.error.waypoint_index_range",
                    index, target.size() - 1));
            return 0;
        }
        String displayLabel = target.displayIndexLabel(index);
        Waypoint removed = removeWaypointAt(target, index);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.removed",
                index, displayLabel, removed.x(), removed.y(), removed.z()));
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
        if (zone == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_zone"));
            return 0;
        }
        List<WaypointGroup> here = manager.groupsForZone(zone.id());
        if (here.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.clear.empty", zone.displayName()));
            return 0;
        }
        if (!confirmed) {
            warn(src, Component.translatable(
                    "waypointer.command.clear.confirm",
                    here.size(), zone.displayName()));
            return 0;
        }
        int cleared = clearCurrentZoneGroups(manager, true);
        success(src, Component.translatable(
                "waypointer.command.clear.success",
                here.size(), zone.displayName()));
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
        if (toExport.isEmpty()) {
            info(src, zone == null
                    ? Component.translatable("waypointer.command.export.empty")
                    : Component.translatable(
                            "waypointer.command.export.empty_in_zone",
                            zone.displayName()));
            return 0;
        }

        String payload = WaypointCodec.encode(toExport, opts);
        boolean copied = setClipboard(payload);

        MutableComponent line = Component.translatable(
                        "waypointer.command.export.success",
                        toExport.size(), payload.length())
                .withStyle(ChatFormatting.GREEN);
        if (!opts.includeNames) {
            line.append(Component.translatable(
                    "waypointer.command.export.without_names")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (copied) {
            line.append(Component.translatable(
                    "waypointer.command.export.copied")
                    .withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.translatable("waypointer.command.export.copy")
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
            error(src, Component.translatable(
                    "waypointer.command.import.clipboard_empty",
                    WaypointCodec.MAGIC));
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
        if (!Files.isRegularFile(path)) {
            error(src, Component.translatable(
                    "waypointer.command.import.no_file", path));
            return 0;
        }

        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            error(src, Component.translatable(
                    "waypointer.command.import.stat_failed", path, e.getMessage()));
            return 0;
        }
        if (size > IMPORT_FILE_MAX_BYTES) {
            error(src, Component.translatable(
                    "waypointer.command.import.file_too_large",
                    size, IMPORT_FILE_MAX_BYTES));
            return 0;
        }

        String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            error(src, Component.translatable(
                    "waypointer.command.import.read_failed", path, e.getMessage()));
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
            error(src, Component.translatable(
                    "waypointer.command.import.expired"));
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
            success(src, Component.translatable(
                    "waypointer.command.import.success",
                    result.groups().size(), origin, result.source()));
            if (retargeted > 0 && targetZone != null) {
                info(src, Component.translatable(
                        retargeted == 1
                                ? "waypointer.command.import.retargeted.one"
                                : "waypointer.command.import.retargeted.many",
                        retargeted, targetZone.displayName()));
            }
            // Surface the sender's label as a separate gray line so it doesn't
            // visually compete with the success line. The label is sanitized
            // by the codec at decode time, but we still emit it as a literal
            // wrapped in quotes to make any whitespace-only tampering obvious.
            if (!result.label().isEmpty()) {
                info(src, Component.translatable(
                        "waypointer.command.import.label",
                        Component.literal(result.label())
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
            error(src, Component.translatable(
                    "waypointer.command.import.failed", e.getMessage()));
            ImportFeedback.failure("Invalid import text.");
            return 0;
        }
    }

    static Component importEditorHintComponent() {
        return Component.translatable("waypointer.command.import.open_editor_hint")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.translatable("waypointer.command.import.open_editor")
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
        success(src, Component.translatable(
                "waypointer.command.route.created", name, zone.displayName()));
        return 1;
    }

    private int runListGroups(FabricClientCommandSource src) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (all.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.route.list_empty"));
            return 0;
        }
        info(src, Component.translatable(
                "waypointer.command.route.list_total", all.size()));
        for (int i = 0; i < all.size(); i++) {
            WaypointGroup g = all.get(i);
            info(src, Component.translatable(
                    g.enabled()
                            ? "waypointer.command.route.list_entry"
                            : "waypointer.command.route.list_entry_disabled",
                    i, g.name(), g.zoneId(), g.size()));
        }
        return all.size();
    }

    private int runDeleteGroup(FabricClientCommandSource src, int index, boolean confirmed) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_index_range",
                    index, all.size() - 1));
            return 0;
        }
        WaypointGroup g = all.get(index);
        if (!confirmed) {
            warn(src, Component.translatable(
                    "waypointer.command.route.delete_confirm",
                    index, g.name(), g.size()));
            return 0;
        }
        manager.remove(g.id());
        success(src, Component.translatable(
                "waypointer.command.route.deleted", g.name()));
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

    private static void info(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(msg));
    }

    private static void success(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.GREEN)));
    }

    private static void warn(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.YELLOW)));
    }

    private static void error(FabricClientCommandSource src, Component msg) {
        src.sendError(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.RED)));
    }
}
