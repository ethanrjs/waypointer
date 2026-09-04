package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.input.WaypointRepositionMode;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.screen.DebugInspectScreen;
import com.babbur.waypointer.screen.RouteCatalogScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class WaypointerCommands {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final Runnable openGui;
    private final WaypointerCommandSuggestions suggestions;
    private final WaypointerRouteCommands routeCommands;
    private final WaypointerGroupCommands groupCommands;
    private final WaypointerChatCommands chatCommands;
    private final WaypointerShareCommands shareCommands;

    public WaypointerCommands(ActiveGroupManager manager, Storage storage,
                              WaypointerConfig config, ChatImportCache chatImportCache,
                              Runnable openGui) {
        this.manager = manager;
        this.config = config;
        this.openGui = openGui;
        this.suggestions = new WaypointerCommandSuggestions(
                manager, storage, config, chatImportCache);
        this.routeCommands = new WaypointerRouteCommands(manager, config, suggestions);
        this.groupCommands = new WaypointerGroupCommands(manager, config, suggestions);
        this.chatCommands = new WaypointerChatCommands(manager, config);
        this.shareCommands = new WaypointerShareCommands(
                manager, config, chatImportCache);
    }

    public void install() {
        WaypointerCommandSuggestionOverride suggestionOverride = new WaypointerCommandSuggestionOverride();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            LiteralCommandNode<FabricClientCommandSource> root = register(dispatcher, "wp");
            registerAlias(dispatcher, "wptr", root);
            registerAlias(dispatcher, "waypointer", root);
            dispatcher.register(literal("happysnowman").executes(ctx -> {
                HappySnowmanSession.activate();
                info(ctx.getSource(), Component.translatable(
                        "waypointer.command.happy_snowman.enabled"));
                return 1;
            }));
            suggestionOverride.setClientRoot(root);
        });
        suggestionOverride.install();
    }

    LiteralCommandNode<FabricClientCommandSource> registerAlias(
            CommandDispatcher<FabricClientCommandSource> dispatcher, String alias,
            LiteralCommandNode<FabricClientCommandSource> root) {
        return dispatcher.register(literal(alias)
                .executes(ctx -> { scheduleOpenGui(); return 1; })
                .redirect(root));
    }

    private LiteralCommandNode<FabricClientCommandSource> register(
            CommandDispatcher<FabricClientCommandSource> d, String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal(root)
                .executes(ctx -> { scheduleOpenGui(); return 1; })
                .then(literal("gui")
                        .executes(ctx -> { scheduleOpenGui(); return 1; })
                        .then(literal("dungeon")
                                .executes(ctx -> { scheduleOpenDungeonGui(); return 1; })))
                .then(literal("help")
                        .executes(ctx -> WaypointerCommandHelp.run(ctx.getSource(), root, null))
                        .then(argument("target", StringArgumentType.word())
                                .suggests(WaypointerCommandHelp.suggestions())
                                .executes(ctx -> WaypointerCommandHelp.run(ctx.getSource(), root,
                                        StringArgumentType.getString(ctx, "target")))))
                .then(literal("list").executes(ctx -> routeCommands.runList(ctx.getSource())))
                .then(literal("routes").executes(ctx -> {
                    scheduleOpenRouteCatalog();
                    return 1;
                }))
                .then(literal("skip").executes(ctx -> routeCommands.runSkipCurrentWaypoint(ctx.getSource())))
                .then(literal("unskip").executes(ctx -> routeCommands.runUnskipCurrentWaypoint(ctx.getSource())))
                .then(literal("skipto")
                        .then(argument("target", StringArgumentType.word())
                                .suggests(suggestions.suggestSkipTargets())
                                .executes(ctx -> routeCommands.runSkipTo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "target")))))
                .then(routeCommands.currentSubwayCommand("sub"))
                .then(routeCommands.currentFlagCommand("tiny", Waypoint.FLAG_SMALL_SUBWAYPOINT,
                        "waypointer.command.waypoint.flag.tiny", true))
                .then(routeCommands.currentFlagCommand("filled", Waypoint.FLAG_FILLED_SUBWAYPOINT,
                        "waypointer.command.waypoint.flag.filled", true))
                .then(routeCommands.currentFlagCommand("hap", Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                        "waypointer.command.waypoint.flag.hide_after_parent", true))
                .then(routeCommands.currentFlagCommand("sts", Waypoint.FLAG_SKIP_ON_STAND,
                        "waypointer.command.waypoint.flag.stand_to_skip", false))
                .then(routeCommands.currentFlagCommand("its", Waypoint.FLAG_SKIP_ON_INTERACT,
                        "waypointer.command.waypoint.flag.interact_to_skip", false))
                .then(routeCommands.currentFlagCommand("los", Waypoint.FLAG_DEPTH_CHECKED,
                        "waypointer.command.waypoint.flag.line_of_sight", false))
                .then(literal("reset").executes(ctx -> routeCommands.runResetActiveGroup(ctx.getSource())))
                .then(literal("mode")
                        .then(argument("mode", StringArgumentType.word())
                                .suggests(suggestions.suggestLoadModes())
                                .executes(ctx -> routeCommands.runSetActiveGroupMode(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode")))))
                .then(literal("radius")
                        .then(argument("radius", DoubleArgumentType.doubleArg(
                                Waypoint.MIN_REACH_RADIUS, Waypoint.MAX_REACH_RADIUS))
                                .executes(ctx -> routeCommands.runSetActiveGroupRadius(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "radius")))))
                .then(literal("move")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .then(argument("slot", IntegerArgumentType.integer(1))
                                        .suggests(suggestions.suggestActiveGroupIndices())
                                        .executes(ctx -> routeCommands.runMoveWaypointToSlot(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                IntegerArgumentType.getInteger(ctx, "slot"))))))
                .then(literal("add")
                        .executes(ctx -> routeCommands.runAdd(ctx.getSource(), ""))
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Z))
                                                        .executes(ctx -> routeCommands.runAddAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("name", StringArgumentType.greedyString())
                                                                .executes(ctx -> routeCommands.runAddAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "name"))))))))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> routeCommands.runAdd(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("addtemp")
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Z))
                                                        .executes(ctx -> chatCommands.runAddTempAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("source", StringArgumentType.greedyString())
                                                                .executes(ctx -> chatCommands.runAddTempAt(ctx.getSource(),
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
                                                                .executes(ctx -> chatCommands.runChatTempClick(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "sender"),
                                                                        StringArgumentType.getString(ctx, "source")))))))))
                .then(literal("blacklist")
                        .executes(ctx -> chatCommands.runChatCoordBlacklist(ctx.getSource()))
                        .then(literal("add")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> chatCommands.runChatCoordBlacklistAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(literal("remove")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> chatCommands.runChatCoordBlacklistRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("remove")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .executes(ctx -> routeCommands.runRemove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index")))))
                .then(routeCommands.insertCommand())
                .then(literal("clear")
                        .executes(ctx -> routeCommands.runClearZone(ctx.getSource(), false))
                        .then(literal("confirm").executes(ctx -> routeCommands.runClearZone(ctx.getSource(), true))))
                .then(literal("export")
                        .executes(ctx -> shareCommands.runExport(ctx.getSource(), exportOptionsFromConfig()))
                        .then(literal("routes")
                                .executes(ctx -> shareCommands.runExportRoutes(
                                        ctx.getSource(), exportOptionsFromConfig())))
                        .then(literal("config")
                                .executes(ctx -> shareCommands.runExportConfig(ctx.getSource())))
                        .then(literal("dungeon")
                                .executes(ctx -> shareCommands.runExportDungeon(ctx.getSource())))
                        .then(literal("bare")
                                .executes(ctx -> shareCommands.runExport(
                                        ctx.getSource(), WaypointCodec.Options.BARE_COORDINATES)))
                        .then(literal("names")
                                .executes(ctx -> shareCommands.runExport(ctx.getSource(), WaypointCodec.Options.WITH_NAMES)))
                        .then(literal("nonames")
                                .executes(ctx -> shareCommands.runExport(ctx.getSource(), WaypointCodec.Options.NO_NAMES))))
                .then(literal("import")
                        .executes(ctx -> shareCommands.runImportFromClipboard(ctx.getSource()))
                        .then(argument("payload", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestImportPayloads())
                                .executes(ctx -> shareCommands.runImportArgument(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "payload")))))
                .then(literal("importfile")
                        .then(argument("path", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestImportFiles())
                                .executes(ctx -> shareCommands.runImportFile(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "path")))))
                .then(literal("debug").executes(ctx -> { scheduleOpenDebugInspector(); return 1; }))
                .then(literal("editmode").executes(this::runToggleEditModeCommand))
                .then(literal("edit")
                        .then(literal("mode").executes(this::runToggleEditModeCommand)))
                .then(literal("importchat")
                        .then(argument("handle", StringArgumentType.word())
                                .suggests(suggestions.suggestChatHandles())
                                .executes(ctx -> shareCommands.runImportChat(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "handle"))))
                        .then(literal("config")
                                .then(argument("handle", StringArgumentType.word())
                                        .suggests(suggestions.suggestChatHandles())
                                        .executes(ctx -> shareCommands.runImportChatTyped(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "handle"),
                                                UniversalShareCodec.Type.CONFIG))))
                        .then(literal("dungeon")
                                .then(argument("handle", StringArgumentType.word())
                                        .suggests(suggestions.suggestChatHandles())
                                        .executes(ctx -> shareCommands.runImportChatTyped(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "handle"),
                                                UniversalShareCodec.Type.DUNGEON)))))
                .then(routeCommands.waypointCommand())
                .then(groupCommands.areaCommand())
                .then(groupCommands.groupCommand("route"))
                .then(groupCommands.groupCommand("group"));
        return d.register(cmd);
    }

    private void scheduleOpenGui() {
        Minecraft.getInstance().execute(openGui);
    }

    private void scheduleOpenDungeonGui() {
        Minecraft.getInstance().execute(
                () -> WaypointerScreen.openDungeonRooms(manager, config));
    }

    private void scheduleOpenRouteCatalog() {
        Minecraft.getInstance().execute(() -> RouteCatalogScreen.open(null));
    }

    private void scheduleOpenDebugInspector() {
        Minecraft.getInstance().execute(() -> DebugInspectScreen.open(null, manager, config));
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

    record SkipToOutcome(int moved, String firstMovedLabel, String error) {}

    static int suggestSkipTargets(List<WaypointGroup> groups, SuggestionsBuilder builder) {
        return WaypointerCommandSuggestions.suggestSkipTargets(groups, builder);
    }

    static String activeGroupIndexTooltip(WaypointGroup group, int index) {
        return WaypointerCommandSuggestions.activeGroupIndexTooltip(group, index);
    }

    static SkipToOutcome skipActiveGroupsToTarget(List<WaypointGroup> activeGroups, String target) {
        return WaypointerRouteCommands.skipActiveGroupsToTarget(activeGroups, target);
    }

    static int waypointIndexFromNumber(int number) {
        return WaypointerRouteCommands.waypointIndexFromNumber(number);
    }

    static Integer parseRgb(String rawColor) {
        return WaypointerRouteCommands.parseRgb(rawColor);
    }

    static int addPersistentWaypointAt(ActiveGroupManager manager, WaypointerConfig config,
                                       WaypointAddFlow addFlow, int x, int y, int z,
                                       String name) {
        return WaypointerRouteCommands.addPersistentWaypointAt(
                manager, config, addFlow, x, y, z, name);
    }

    static int insertPersistentWaypointAt(WaypointGroup target, WaypointerConfig config,
                                          WaypointAddFlow addFlow, int index,
                                          int x, int y, int z, String name) {
        return WaypointerRouteCommands.insertPersistentWaypointAt(
                target, config, addFlow, index, x, y, z, name);
    }

    static boolean definitionOnlyRouteRequiresConversion(ActiveGroupManager manager) {
        return WaypointerRouteCommands.definitionOnlyRouteRequiresConversion(manager);
    }

    static Waypoint removeWaypointAt(WaypointGroup target, int index) {
        return WaypointerRouteCommands.removeWaypointAt(target, index);
    }

    static int clearCurrentZoneGroups(ActiveGroupManager manager, boolean confirmed) {
        return WaypointerRouteCommands.clearCurrentZoneGroups(manager, confirmed);
    }

    static MutableComponent exportSuccessMessage(int routeCount, String payload,
                                                 WaypointCodec.Options options, boolean copied) {
        return WaypointerShareCommands.exportSuccessMessage(
                routeCount, payload, options, copied);
    }

    static List<WaypointGroup> cliExportGroups(ActiveGroupManager manager) {
        return WaypointerShareCommands.cliExportGroups(manager);
    }

    static List<WaypointGroup> cliBulkExportGroups(ActiveGroupManager manager) {
        return WaypointerShareCommands.cliBulkExportGroups(manager);
    }

    static MutableComponent importSuccessMessage(int routeCount, String origin, Object source,
                                                 int retargeted, Zone targetZone, String label) {
        return WaypointerShareCommands.importSuccessMessage(
                routeCount, origin, source, retargeted, targetZone, label);
    }

    static Component importEditorHintComponent() {
        return WaypointerShareCommands.importEditorHintComponent();
    }

    private static int retargetUnknownGroups(List<WaypointGroup> groups, Zone target) {
        return WaypointerShareCommands.retargetUnknownGroups(groups, target);
    }

    private WaypointCodec.Options exportOptionsFromConfig() {
        return shareCommands.exportOptionsFromConfig();
    }
}
