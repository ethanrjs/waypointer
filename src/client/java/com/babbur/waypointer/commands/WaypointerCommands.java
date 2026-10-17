package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.compat.MinecraftCompat;
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
import com.babbur.waypointer.screen.CrystalHollowsMinimapEditorScreen;
import com.babbur.waypointer.screen.RouteCatalogScreen;
import com.babbur.waypointer.screen.SettingsScreen;
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
    private final WaypointerWaypointCommands waypointCommands;
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
        this.waypointCommands = new WaypointerWaypointCommands(manager, config, suggestions, routeCommands);
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
                .then(literal("help")
                        .executes(ctx -> WaypointerCommandHelp.run(ctx.getSource(), root, null))
                        .then(argument("topic", StringArgumentType.word())
                                .suggests(WaypointerCommandHelp.suggestions())
                                .executes(ctx -> WaypointerCommandHelp.run(ctx.getSource(), root,
                                        StringArgumentType.getString(ctx, "topic")))))
                // Screens
                .then(literal("gui")
                        .executes(ctx -> { scheduleOpenGui(); return 1; })
                        .then(literal("dungeon")
                                .executes(ctx -> { scheduleOpenDungeonGui(); return 1; }))
                        .then(argument("groupId", StringArgumentType.string())
                                .executes(ctx -> runOpenFocusedGui(
                                        StringArgumentType.getString(ctx, "groupId")))))
                .then(literal("settings").executes(ctx -> {
                    SettingsScreen.open(config);
                    return 1;
                }))
                .then(literal("community").executes(ctx -> { scheduleOpenRouteCatalog(); return 1; }))
                .then(literal("routes").executes(ctx -> { scheduleOpenRouteCatalog(); return 1; }))
                .then(routeCommands.editCommand()
                        .then(literal("mode").executes(this::runToggleEditModeCommand)))
                .then(literal("editmode").executes(this::runToggleEditModeCommand))
                .then(routeCommands.appearanceCommand())
                .then(routeCommands.publishCommand())
                .then(literal("minimap").executes(ctx -> {
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> MinecraftCompat.setScreen(client,
                            new CrystalHollowsMinimapEditorScreen(null, config)));
                    return 1;
                }))
                .then(literal("debug").executes(ctx -> { scheduleOpenDebugInspector(); return 1; }))
                // Which route commands act on
                .then(routeCommands.listCommand())
                .then(routeCommands.targetCommand())
                .then(waypointCommands.waypointsCommand())
                // Routes and folders
                .then(routeCommands.routeCommand())
                .then(routeCommands.folderCommand())
                .then(routeCommands.visibilityCommand("show", true))
                .then(routeCommands.visibilityCommand("hide", false))
                .then(routeCommands.clearCommand())
                // Waypoints
                .then(waypointCommands.addCommand())
                .then(waypointCommands.insertCommand())
                .then(waypointCommands.removeCommand())
                .then(waypointCommands.moveCommand())
                .then(waypointCommands.waypointCommand())
                // Progress
                .then(waypointCommands.skipCommand())
                .then(waypointCommands.unskipCommand())
                .then(waypointCommands.skipToCommand())
                .then(waypointCommands.resetCommand())
                // Temporary waypoints and chat
                .then(literal("addtemp")
                        .executes(ctx -> chatCommands.runAddTempHere(ctx.getSource()))
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
                // Sharing
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
                                                UniversalShareCodec.Type.DUNGEON))))
                        .then(literal("catalog")
                                .then(argument("handle", StringArgumentType.word())
                                        .suggests(suggestions.suggestChatHandles())
                                        .executes(ctx -> shareCommands.runImportChatTyped(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "handle"),
                                                UniversalShareCodec.Type.CATALOG)))));
        return d.register(cmd);
    }

    private void scheduleOpenGui() {
        Minecraft.getInstance().execute(openGui);
    }

    private void scheduleOpenDungeonGui() {
        Minecraft.getInstance().execute(
                () -> WaypointerScreen.openDungeonRooms(manager, config));
    }

    private int runOpenFocusedGui(String groupId) {
        Minecraft.getInstance().execute(() -> {
            WaypointGroup focus = resolveGuiFocus(manager, groupId);
            if (focus == null) {
                openGui.run();
            } else {
                WaypointerScreen.openFocused(manager, config, focus);
            }
        });
        return 1;
    }

    static WaypointGroup resolveGuiFocus(ActiveGroupManager manager, String groupId) {
        return manager == null || groupId == null || groupId.isBlank()
                ? null : manager.get(groupId);
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

    static int addPersistentWaypointAt(ActiveGroupManager manager, WaypointerConfig config,
                                       WaypointAddFlow addFlow, int x, int y, int z,
                                       String name) {
        return WaypointerWaypointCommands.addPersistentWaypointAt(
                manager, config, addFlow, x, y, z, name);
    }

    static int insertPersistentWaypointAt(WaypointGroup target, WaypointerConfig config,
                                          WaypointAddFlow addFlow, int index,
                                          int x, int y, int z, String name) {
        return WaypointerWaypointCommands.insertPersistentWaypointAt(
                target, config, addFlow, index, x, y, z, name);
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
