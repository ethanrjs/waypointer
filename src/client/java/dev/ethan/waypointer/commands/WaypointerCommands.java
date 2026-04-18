package dev.ethan.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.chat.ChatImportCache;
import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.config.Storage;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.screen.DebugInspectScreen;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers {@code /waypointer} and its alias {@code /wp} as client-side commands.
 *
 * We lean on Brigadier's help-text for each subcommand's usage; the feedback messages
 * intentionally use the same vocabulary as the in-game UI ("group", "waypoint", "zone")
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

    public WaypointerCommands(ActiveGroupManager manager, Storage storage,
                              WaypointerConfig config, ChatImportCache chatImportCache,
                              Runnable openGui) {
        this.manager = manager;
        this.storage = storage;
        this.config = config;
        this.chatImportCache = chatImportCache;
        this.openGui = openGui;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            register(dispatcher, "waypointer");
            register(dispatcher, "wp");
        });
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d, String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal(root)
                .executes(ctx -> { scheduleOpenGui(); return 1; })
                .then(literal("gui").executes(ctx -> { scheduleOpenGui(); return 1; }))
                // /wp help                  -> page 1
                // /wp help <n>              -> nth page (1-based)
                // /wp help <section>        -> jump to a section by name/alias
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
                // "add" uses an explicit "at" literal for coord input so we never have to
                // disambiguate "/wp add 100" (a name) from "/wp add 100 64 200" (coords).
                // Brigadier's greedy-string fallback was flagging ambiguity warnings and --
                // more importantly -- would treat a numeric name as a failed coord parse.
                .then(literal("add")
                        .executes(ctx -> runAdd(ctx.getSource(), ""))
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
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
                .then(literal("remove")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestActiveGroupIndices())
                                .executes(ctx -> runRemove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index")))))
                // "insert" reuses the player's current position so the workflow is
                // identical to /wp add -- you stand where you want it, you type the
                // index, and you're done. Coord-form insertion would just be a copy
                // of /wp add at; if a user wants that, they can add then move.
                .then(literal("insert")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestInsertSlots())
                                .executes(ctx -> runInsert(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), ""))
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runInsert(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "name"))))))
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
                                .executes(ctx -> runImport(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "payload"), "argument"))))
                .then(literal("importfile")
                        .then(argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> runImportFile(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "path")))))
                .then(literal("debug").executes(ctx -> { scheduleOpenDebugInspector(); return 1; }))
                .then(literal("importchat")
                        .then(argument("handle", StringArgumentType.word())
                                .suggests(suggestChatHandles())
                                .executes(ctx -> runImportChat(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "handle")))))
                .then(literal("group")
                        .then(literal("create")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runCreateGroup(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(literal("list").executes(ctx -> runListGroups(ctx.getSource())))
                        .then(literal("delete")
                                .then(argument("index", IntegerArgumentType.integer(0))
                                        .suggests(suggestAllGroupIndices())
                                        .executes(ctx -> runDeleteGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"))))));
        d.register(cmd);
    }

    // --- tab-complete suggestion providers ----------------------------------------------------

    /**
     * Suggests numeric indices into the first active group's waypoints, with
     * the waypoint's short label as the Brigadier tooltip. Mirrors what the
     * user would see in {@code /wp list}, so the completion is self-documenting
     * -- they don't have to run list, pick an index, and then re-type.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestActiveGroupIndices() {
        return (ctx, builder) -> {
            WaypointGroup g = manager.firstActiveGroup();
            if (g == null) return builder.buildFuture();
            return suggestIndexed(builder, g.size(), i -> describeWaypoint(g.get(i)));
        };
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
                        ? "append (after " + (size - 1) + ")"
                        : "before " + describeWaypoint(g.get(i));
                builder.suggest(i, Component.literal(tip));
            }
            return builder.buildFuture();
        };
    }

    /**
     * Suggests numeric indices into the full group list, with each group's
     * name + point count as a tooltip so an accidental {@code delete 4} is
     * harder to mis-fire.
     */
    private SuggestionProvider<FabricClientCommandSource> suggestAllGroupIndices() {
        return (ctx, builder) -> {
            List<WaypointGroup> all = manager.allGroups();
            return suggestIndexed(builder, all.size(),
                    i -> all.get(i).name() + " (" + all.get(i).size() + " pts)");
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

    /**
     * Shared helper: emit integer suggestions 0..count-1 that match the prefix
     * the user has typed so far, each annotated with a tooltip produced by
     * {@code tooltipFor}. Brigadier only re-sorts numerically when the raw
     * suggestion is parseable as an int, so we pass the number as a string
     * and let the framework handle the ordering.
     */
    private static CompletableFuture<Suggestions> suggestIndexed(
            SuggestionsBuilder builder, int count,
            java.util.function.IntFunction<String> tooltipFor) {
        String prefix = builder.getRemaining();
        for (int i = 0; i < count; i++) {
            String s = Integer.toString(i);
            if (!s.startsWith(prefix)) continue;
            builder.suggest(i, Component.literal(tooltipFor.apply(i)));
        }
        return builder.buildFuture();
    }

    private static String describeWaypoint(Waypoint w) {
        String coords = w.x() + ", " + w.y() + ", " + w.z();
        return w.hasName() ? w.name() + "  " + coords : coords;
    }

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
        Minecraft.getInstance().execute(() -> DebugInspectScreen.open(null));
    }

    /**
     * Target left-column width for help rows. Chosen to match the longest usage
     * we print ("/wp export [names|nonames]" at 26 chars) plus one space of
     * breathing room before the " -- " separator. Chat is not a monospace
     * surface, but padding to a consistent character column still visibly
     * aligns the descriptions because spaces render at a fixed narrow pixel
     * width and the usage lines are all ASCII.
     */
    private static final int HELP_USAGE_COLUMN = 27;

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
                            new HelpRow("",                         "open the editor GUI"),
                            new HelpRow(" gui",                     "open the editor GUI"),
                            new HelpRow(" list",                    "list active groups and waypoints"),
                            new HelpRow(" help [page|section]",     "show this help; page number or section name"))),
            new HelpSection("editing", "Waypoint editing",
                    List.of(
                            new HelpRow(" add [name]",              "add waypoint at your position"),
                            new HelpRow(" add at <x> <y> <z>",      "add waypoint at the given coordinates"),
                            new HelpRow(" insert [index] [name]",  "insert at index, your position"),
                            new HelpRow(" remove <index>",          "remove waypoint by index"),
                            new HelpRow(" clear [confirm]",         "clear all groups in current zone"))),
            new HelpSection("sharing", "Sharing (import/export)",
                    List.of(
                            new HelpRow(" export [names|nonames]",  "copy route to clipboard as a codec"),
                            new HelpRow(" import [payload]",        "import from clipboard or inline payload"),
                            new HelpRow(" importfile <path>",       "import a JSON file (e.g. coleweight)"),
                            new HelpRow(" importchat <handle>",     "import from a chat pill"))),
            new HelpSection("groups", "Groups & debug",
                    List.of(
                            new HelpRow(" group create <name>",     "make a new group in the current zone"),
                            new HelpRow(" group list",              "list every group across zones"),
                            new HelpRow(" group delete <index>",    "delete a group by list index"),
                            new HelpRow(" debug",                   "inspect a pasted codec's wire format")))
    );

    /**
     * Render one page of the paginated help.
     *
     * @param target null → page 1; digits → that page (1-based); otherwise a
     *               section id / title substring.
     */
    private int runHelp(FabricClientCommandSource src, String root, String target) {
        int pageIdx = resolveHelpPage(target);
        if (pageIdx < 0) {
            error(src, "Unknown help section: '" + target + "'. Try /" + root + " help for page 1.");
            return 0;
        }

        HelpSection section = HELP_SECTIONS.get(pageIdx);
        int totalPages = HELP_SECTIONS.size();
        String prefix = "/" + root;

        info(src, Component.literal("Waypointer help -- ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(section.title()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (page " + (pageIdx + 1) + "/" + totalPages + ")")
                        .withStyle(ChatFormatting.DARK_GRAY)));

        for (HelpRow row : section.rows()) {
            helpLine(src, prefix + row.usage(), row.description());
        }

        renderHelpFooter(src, root, pageIdx, totalPages);
        return 1;
    }

    /**
     * Resolves a user-supplied help target to a zero-based section index, or
     * -1 for an unrecognized target. {@code null} (no arg) is page 1. Digits
     * map to the 1-based page number. Any other word is matched against
     * section ids and title prefixes, case-insensitively, so both
     * {@code /wp help groups} and {@code /wp help group} land on the same
     * page.
     */
    private static int resolveHelpPage(String target) {
        if (target == null || target.isBlank()) return 0;
        String t = target.trim().toLowerCase(Locale.ROOT);

        if (t.chars().allMatch(Character::isDigit)) {
            int page = Integer.parseInt(t) - 1;
            return (page >= 0 && page < HELP_SECTIONS.size()) ? page : -1;
        }

        for (int i = 0; i < HELP_SECTIONS.size(); i++) {
            HelpSection s = HELP_SECTIONS.get(i);
            if (s.id().equals(t) || s.title().toLowerCase(Locale.ROOT).startsWith(t)) return i;
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
    private void renderHelpFooter(FabricClientCommandSource src, String root,
                                  int pageIdx, int totalPages) {
        MutableComponent footer = Component.empty();

        boolean hasPrev = pageIdx > 0;
        MutableComponent prev = Component.literal("<< prev");
        prev.withStyle(hasPrev
                ? Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + root + " help " + pageIdx))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.literal("Page " + pageIdx + "/" + totalPages)))
                : Style.EMPTY.withColor(ChatFormatting.DARK_GRAY));
        footer.append(prev);

        footer.append(Component.literal("  .  ").withStyle(ChatFormatting.DARK_GRAY));

        boolean hasNext = pageIdx + 1 < totalPages;
        MutableComponent next = Component.literal("next >>");
        next.withStyle(hasNext
                ? Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + root + " help " + (pageIdx + 2)))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.literal("Page " + (pageIdx + 2) + "/" + totalPages)))
                : Style.EMPTY.withColor(ChatFormatting.DARK_GRAY));
        footer.append(next);

        footer.append(Component.literal("    sections: ").withStyle(ChatFormatting.DARK_GRAY));
        // Append jump links for every section so users can skip directly. We
        // render the current page's link dim (unclickable) to make the
        // "you are here" state visible without a separate marker.
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
            if (i < HELP_SECTIONS.size() - 1) {
                footer.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        src.sendFeedback(footer);
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
            return builder.buildFuture();
        };
    }

    /**
     * Emit a single help row with the usage padded to {@link #HELP_USAGE_COLUMN}
     * so every {@code " -- description"} separator starts at the same visual
     * column. If a usage ever exceeds the column width we let it overflow and
     * still print the description -- truncating usage would be worse than
     * mildly misaligned output.
     */
    private static void helpLine(FabricClientCommandSource src, String usage, String description) {
        StringBuilder sb = new StringBuilder(usage);
        while (sb.length() < HELP_USAGE_COLUMN) sb.append(' ');
        sb.append(" -- ").append(description);
        info(src, sb.toString());
    }

    /** One row in a help section: the usage shape (minus the root prefix) and what it does. */
    private record HelpRow(String usage, String description) {}

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
            info(src, "No active waypoint groups in this zone" + zoneSuffix());
            return 0;
        }
        for (WaypointGroup g : active) {
            info(src, Component.literal("Group: ")
                    .append(Component.literal(g.name()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (" + g.size() + " points, @" + g.currentIndex() + ")")
                            .withStyle(ChatFormatting.GRAY)));
            int shown = Math.min(g.size(), 16);
            for (int i = 0; i < shown; i++) {
                Waypoint w = g.get(i);
                ChatFormatting color = i < g.currentIndex() ? ChatFormatting.DARK_GRAY
                        : i == g.currentIndex() ? ChatFormatting.YELLOW
                        : ChatFormatting.WHITE;
                info(src, Component.literal("  [" + i + "] ")
                        .append(Component.literal(w.x() + ", " + w.y() + ", " + w.z()).withStyle(color))
                        .append(w.hasName() ? Component.literal(" " + w.name()).withStyle(ChatFormatting.GRAY)
                                : Component.empty()));
            }
            if (g.size() > shown) info(src, "  ... " + (g.size() - shown) + " more");
        }
        return active.size();
    }

    private int runAdd(FabricClientCommandSource src, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { error(src, "Not in a world"); return 0; }

        return runAddAt(src,
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                name);
    }

    private int runAddAt(FabricClientCommandSource src, int x, int y, int z, String name) {
        WaypointGroup target = manager.getOrCreateActiveGroup();
        target.add(new Waypoint(x, y, z, name == null ? "" : name,
                Waypoint.DEFAULT_COLOR, 0, 0.0));
        manager.fireDataChanged();

        success(src, "Added waypoint " + (target.size() - 1) + " to \"" + target.name()
                + "\" at " + x + ", " + y + ", " + z);
        return 1;
    }

    /**
     * Inserts a new waypoint at the player's position into the active group's
     * waypoint list at {@code index}. {@code index == size} appends, matching
     * the semantics of {@link java.util.List#add(int, Object)} which
     * {@link WaypointGroup#insert(int, Waypoint)} delegates to.
     */
    private int runInsert(FabricClientCommandSource src, int index, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { error(src, "Not in a world"); return 0; }

        WaypointGroup target = manager.getOrCreateActiveGroup();
        if (index < 0 || index > target.size()) {
            // Mirror the inclusive upper bound from the suggest tooltip so the
            // error message and the completion list agree on what's legal.
            error(src, "Index " + index + " out of range (0.." + target.size() + ")");
            return 0;
        }

        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        target.insert(index, new Waypoint(x, y, z, name == null ? "" : name,
                Waypoint.DEFAULT_COLOR, 0, 0.0));
        manager.fireDataChanged();

        success(src, "Inserted waypoint at [" + index + "] in \"" + target.name()
                + "\" at " + x + ", " + y + ", " + z);
        return 1;
    }

    private int runRemove(FabricClientCommandSource src, int index) {
        WaypointGroup target = manager.firstActiveGroup();
        if (target == null) { error(src, "No active group to remove from"); return 0; }
        if (index < 0 || index >= target.size()) {
            error(src, "Index " + index + " out of range (0.." + (target.size() - 1) + ")");
            return 0;
        }
        Waypoint removed = target.get(index);
        target.remove(index);
        manager.fireDataChanged();
        success(src, "Removed [" + index + "] " + removed.x() + ", " + removed.y() + ", " + removed.z());
        return 1;
    }

    private int runClearZone(FabricClientCommandSource src, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (zone == null) { error(src, "No active zone"); return 0; }
        List<WaypointGroup> here = manager.groupsForZone(zone.id());
        if (here.isEmpty()) { info(src, "Nothing to clear in " + zone.displayName()); return 0; }
        if (!confirmed) {
            warn(src, "This will delete " + here.size() + " group(s) in "
                    + zone.displayName() + ". Run /waypointer clear confirm to proceed.");
            return 0;
        }
        for (WaypointGroup g : here) manager.remove(g.id());
        success(src, "Cleared " + here.size() + " group(s) in " + zone.displayName());
        return here.size();
    }

    private int runExport(FabricClientCommandSource src, WaypointCodec.Options opts) {
        Zone zone = manager.currentZone();
        List<WaypointGroup> toExport = zone == null ? manager.allGroups() : manager.groupsForZone(zone.id());
        if (toExport.isEmpty()) { info(src, "Nothing to export" + zoneSuffix()); return 0; }

        String payload = WaypointCodec.encode(toExport, opts);
        boolean copied = setClipboard(payload);

        MutableComponent line = Component.literal("Exported " + toExport.size() + " group(s) (" + payload.length() + " chars)")
                .withStyle(ChatFormatting.GREEN);
        if (!opts.includeNames) line.append(Component.literal(" without names").withStyle(ChatFormatting.GRAY));
        if (copied) line.append(Component.literal(" (copied to clipboard)").withStyle(ChatFormatting.GRAY));
        line.append(Component.literal(" [click to copy]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(payload))));
        src.sendFeedback(line);
        return toExport.size();
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

    private int runImportChat(FabricClientCommandSource src, String handle) {
        String codec = chatImportCache.get(handle);
        if (codec == null) {
            error(src, "That import button has expired. Ask the sender to repost the codec.");
            return 0;
        }
        return runImport(src, codec, "chat");
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

            for (WaypointGroup g : result.groups()) manager.add(g);
            success(src, "Imported " + result.groups().size() + " group(s) from " + origin
                    + " (format: " + result.source() + ")");
            if (retargeted > 0 && targetZone != null) {
                info(src, retargeted + (retargeted == 1 ? " group" : " groups")
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
            return result.groups().size();
        } catch (IllegalArgumentException e) {
            error(src, "Import failed: " + e.getMessage());
            return 0;
        }
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
     * Default export options reflect the user's config preference. Keeping names off by
     * default favors chat-shareability; users who want them can run {@code /wp export names}.
     *
     * The label is intentionally omitted here: the CLI export path doesn't have a
     * good way to prompt for a label, and silently attaching one from config would
     * surprise users who set it once and forgot. Use {@link dev.ethan.waypointer.screen.ExportScreen}
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
        WaypointGroup g = WaypointGroup.create(name, zone.id());
        manager.add(g);
        success(src, "Created group \"" + name + "\" in " + zone.displayName());
        return 1;
    }

    private int runListGroups(FabricClientCommandSource src) {
        List<WaypointGroup> all = manager.allGroups();
        if (all.isEmpty()) { info(src, "No groups defined."); return 0; }
        info(src, all.size() + " group(s) total:");
        for (int i = 0; i < all.size(); i++) {
            WaypointGroup g = all.get(i);
            info(src, "  [" + i + "] " + g.name() + " -- zone=" + g.zoneId()
                    + " points=" + g.size() + (g.enabled() ? "" : " (disabled)"));
        }
        return all.size();
    }

    private int runDeleteGroup(FabricClientCommandSource src, int index) {
        List<WaypointGroup> all = manager.allGroups();
        if (index < 0 || index >= all.size()) {
            error(src, "Index " + index + " out of range (0.." + (all.size() - 1) + ")");
            return 0;
        }
        WaypointGroup g = all.get(index);
        manager.remove(g.id());
        success(src, "Deleted group \"" + g.name() + "\"");
        return 1;
    }

    // --- helpers ------------------------------------------------------------------------------

    private String zoneSuffix() {
        Zone zone = manager.currentZone();
        return zone == null ? "" : " (" + zone.displayName() + ")";
    }

    // Clipboard access goes through AWT because Minecraft's GLFW clipboard is only safe
    // to call on the render thread during certain phases. AWT works reliably even from
    // the client command thread.
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
            Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = c.getData(DataFlavor.stringFlavor);
            return data == null ? null : data.toString();
        } catch (Throwable t) {
            Waypointer.LOGGER.warn("Clipboard read failed", t);
            return null;
        }
    }

    private static void info(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GRAY));
    }

    private static void info(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(msg);
    }

    private static void success(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GREEN));
    }

    private static void warn(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.YELLOW));
    }

    private static void error(FabricClientCommandSource src, String msg) {
        src.sendError(Component.literal(msg).withStyle(ChatFormatting.RED));
    }
}
