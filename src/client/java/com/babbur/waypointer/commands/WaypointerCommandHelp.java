package com.babbur.waypointer.commands;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WaypointerCommandHelp {

    private static final List<HelpSection> SECTIONS = List.of(
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
                            new HelpRow(" insert <number> [name]", "waypointer.command.help.insert_number",
                                    "insert 2", "insert 2 Secret"),
                            new HelpRow(" insert <number> at <x> <y> <z> [name]", "waypointer.command.help.insert_at",
                                    "insert 2 at 12 70 -4", "insert 2 at 12 70 -4 Chest"),
                            new HelpRow(" remove <number>", "waypointer.command.help.remove_number",
                                    "remove 3"),
                            new HelpRow(" move <number> <position>", "waypointer.command.help.move_number",
                                    "move 4 2"),
                            new HelpRow(" skipto <n[.sub]>", "waypointer.command.help.skipto",
                                    "skipto 3", "skipto 3.2"),
                            new HelpRow(" skip", "waypointer.command.help.skip", "skip"),
                            new HelpRow(" unskip", "waypointer.command.help.unskip", "unskip"),
                            new HelpRow(" reset", "waypointer.command.help.reset", "reset"),
                            new HelpRow(" mode <static|sequence>", "waypointer.command.help.mode",
                                    "mode sequence", "mode static"),
                            new HelpRow(" radius <blocks>", "waypointer.command.help.radius", "radius 4.5"),
                            new HelpRow(" editmode", "waypointer.command.help.edit_mode", "editmode"),
                            new HelpRow(" edit mode", "waypointer.command.help.edit_mode", "edit mode"),
                            new HelpRow(" clear [confirm]", "waypointer.command.help.clear",
                                    "clear", "clear confirm"))),
            new HelpSection("subway", "waypointer.command.help.section.subway",
                    List.of(
                            new HelpRow(" sub [number]", "waypointer.command.help.sub_number", "sub", "sub 4"),
                            new HelpRow(" tiny [index]", "waypointer.command.help.tiny", "tiny", "tiny 4"),
                            new HelpRow(" filled [index]", "waypointer.command.help.filled", "filled", "filled 4"),
                            new HelpRow(" hap [index]", "waypointer.command.help.hap", "hap", "hap 4"),
                            new HelpRow(" sts [number]", "waypointer.command.help.sts_number", "sts", "sts 4"),
                            new HelpRow(" its [number]", "waypointer.command.help.its_number", "its", "its 4"),
                            new HelpRow(" los [number]", "waypointer.command.help.los_number", "los", "los 4"))),
            new HelpSection("waypoint", "waypointer.command.help.section.waypoint",
                    List.of(
                            new HelpRow(" waypoint move <number> here", "waypointer.command.help.waypoint_move_here",
                                    "waypoint move 3 here"),
                            new HelpRow(" waypoint move <number> at <x> <y> <z>", "waypointer.command.help.waypoint_move_at",
                                    "waypoint move 3 at 12 70 -4"),
                            new HelpRow(" waypoint rename <number> <name>", "waypointer.command.help.waypoint_rename",
                                    "waypoint rename 3 Fairy Soul"),
                            new HelpRow(" waypoint color <number> <hex>", "waypointer.command.help.waypoint_color",
                                    "waypoint color 3 58C878"),
                            new HelpRow(" waypoint radius <number> <blocks>", "waypointer.command.help.waypoint_radius",
                                    "waypoint radius 3 1.5"),
                            new HelpRow(" waypoint sub <number>", "waypointer.command.help.waypoint_sub_number",
                                    "waypoint sub 4"))),
            new HelpSection("routes", "waypointer.command.help.section.routes",
                    List.of(
                            new HelpRow(" route create <name>", "waypointer.command.help.route_create",
                                    "route create Foraging Route"),
                            new HelpRow(" route list", "waypointer.command.help.route_list", "route list"),
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
                            new HelpRow(" group ...", "waypointer.command.help.group_alias", "group list"))),
            new HelpSection("sharing", "waypointer.command.help.section.sharing",
                    List.of(
                            new HelpRow(" export [bare|names|nonames]", "waypointer.command.help.export",
                                    "export", "export bare", "export names"),
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
                            new HelpRow(" blacklist", "waypointer.command.help.blacklist", "blacklist"),
                            new HelpRow(" blacklist add <name>", "waypointer.command.help.blacklist_add",
                                    "blacklist add Babbur"),
                            new HelpRow(" blacklist remove <name>", "waypointer.command.help.blacklist_remove",
                                    "blacklist remove Babbur"))),
            new HelpSection("debug", "waypointer.command.help.section.debug",
                    List.of(new HelpRow(" debug", "waypointer.command.help.debug", "debug"))),
            new HelpSection("crystal", "waypointer.command.help.section.crystal",
                    List.of(
                            new HelpRow("/wpch [info]", "waypointer.command.help.crystal.info",
                                    "/wpch", "/wpch info"),
                            new HelpRow("/wpch share <structure>",
                                    "waypointer.command.help.crystal.share",
                                    "/wpch share jungle_temple"),
                            new HelpRow("/wpch add <structure> [x y z]",
                                    "waypointer.command.help.crystal.add",
                                    "/wpch add odawa", "/wpch add odawa 349 110 390"),
                            new HelpRow("/wpch remove <structure> | clear",
                                    "waypointer.command.help.crystal.remove",
                                    "/wpch remove odawa", "/wpch clear"),
                            new HelpRow("/wpch compass [reset]",
                                    "waypointer.command.help.crystal.compass",
                                    "/wpch compass", "/wpch compass reset"),
                            new HelpRow("/wpch toggle <enabled|compass|chat|entities|rough>",
                                    "waypointer.command.help.crystal.toggle",
                                    "/wpch toggle rough")))
    );

    private WaypointerCommandHelp() {}

    static int run(FabricClientCommandSource source, String root, String target) {
        String prefix = "/" + root;
        if (target != null && "all".equalsIgnoreCase(target.trim())) {
            spacer(source);
            feedback(source, Component.translatable("waypointer.command.help.title")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.translatable("waypointer.command.help.hover_hint")
                            .withStyle(ChatFormatting.DARK_GRAY)));
            for (HelpSection section : SECTIONS) {
                renderSection(source, prefix, section);
            }
            renderFooter(source, root, -1);
            spacer(source);
            return 1;
        }

        int pageIndex = resolvePage(target);
        if (pageIndex < 0) {
            source.sendError(WaypointerChatFeedback.suppress(Component.translatable(
                    "waypointer.command.help.unknown", target, "/" + root + " help")
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }

        HelpSection section = SECTIONS.get(pageIndex);
        spacer(source);
        feedback(source, Component.translatable(
                        "waypointer.command.help.page_title",
                        Component.translatable(section.titleKey()))
                .withStyle(ChatFormatting.AQUA));
        renderSection(source, prefix, section);
        renderFooter(source, root, pageIndex);
        spacer(source);
        return 1;
    }

    static int resolvePage(String target) {
        if (target == null || target.isBlank()) return 0;
        String normalized = target.trim().toLowerCase(Locale.ROOT);
        if ("editing".equals(normalized) || "edit".equals(normalized)) return 1;
        if ("flags".equals(normalized)) return 2;
        if ("areas".equals(normalized)) return 4;
        if ("wpch".equals(normalized) || "waypointer-crystal".equals(normalized)) return 8;

        if (normalized.chars().allMatch(Character::isDigit)) {
            int page = Integer.parseInt(normalized) - 1;
            return page >= 0 && page < SECTIONS.size() ? page : -1;
        }

        for (int i = 0; i < SECTIONS.size(); i++) {
            HelpSection section = SECTIONS.get(i);
            String localizedTitle = Component.translatable(section.titleKey()).getString();
            if (section.id().equals(normalized)
                    || (!localizedTitle.equals(section.titleKey())
                            && localizedTitle.toLowerCase(Locale.ROOT).startsWith(normalized))) {
                return i;
            }
            for (HelpRow row : section.rows()) {
                if (commandWord(row.usage()).equals(normalized)) return i;
            }
        }
        return -1;
    }

    static SuggestionProvider<FabricClientCommandSource> suggestions() {
        return (context, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            builder.suggest("all", Component.translatable("waypointer.command.help.show_all"));
            for (int i = 0; i < SECTIONS.size(); i++) {
                String page = Integer.toString(i + 1);
                if (page.startsWith(prefix)) {
                    builder.suggest(page, Component.translatable(SECTIONS.get(i).titleKey()));
                }
            }
            for (HelpSection section : SECTIONS) {
                if (section.id().startsWith(prefix)) {
                    builder.suggest(section.id(), Component.translatable(section.titleKey()));
                }
            }
            Set<String> commandWords = new LinkedHashSet<>();
            for (HelpSection section : SECTIONS) {
                for (HelpRow row : section.rows()) {
                    String command = commandWord(row.usage());
                    if (!command.isEmpty() && commandWords.add(command) && command.startsWith(prefix)) {
                        builder.suggest(command, Component.translatable(section.titleKey()));
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static void renderSection(
            FabricClientCommandSource source, String prefix, HelpSection section) {
        feedback(source, Component.translatable(section.titleKey()).withStyle(ChatFormatting.YELLOW));
        for (HelpRow row : section.rows()) {
            MutableComponent line = highlightedCommand(prefix, row.usage());
            line.withStyle(line.getStyle().withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                    hover(prefix, row))));
            feedback(source, line);
        }
    }

    private static void renderFooter(FabricClientCommandSource source, String root, int pageIndex) {
        MutableComponent footer = Component.empty();
        footer.append(Component.translatable("waypointer.command.help.sections")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (int i = 0; i < SECTIONS.size(); i++) {
            HelpSection section = SECTIONS.get(i);
            boolean current = i == pageIndex;
            MutableComponent jump = Component.literal(section.id());
            jump.withStyle(current
                    ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                    : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/" + root + " help " + section.id()))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                    Component.translatable(section.titleKey()))));
            footer.append(jump);
            footer.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        }

        MutableComponent all = Component.translatable("waypointer.command.help.all");
        all.withStyle(pageIndex < 0
                ? Style.EMPTY.withColor(ChatFormatting.GRAY)
                : Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + root + " help all"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.translatable("waypointer.command.help.show_all"))));
        footer.append(all);
        feedback(source, footer);
    }

    private static Component hover(String prefix, HelpRow row) {
        MutableComponent hover = Component.empty();
        hover.append(Component.translatable(row.descriptionKey()).withStyle(ChatFormatting.YELLOW));
        hover.append(Component.translatable("waypointer.command.help.usage").withStyle(ChatFormatting.AQUA));
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
        if (usage != null && usage.trim().startsWith("/")) {
            prefix = "";
        }
        MutableComponent result = Component.literal(prefix)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA));
        String trimmed = usage == null ? "" : usage.trim();
        if (trimmed.isEmpty()) return result;
        for (String token : trimmed.split(" ")) {
            if (token.isEmpty()) continue;
            result.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
            result.append(highlightedToken(token));
        }
        return result;
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

    private static String commandWord(String usage) {
        String trimmed = usage == null ? "" : usage.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "";
        int firstSpace = trimmed.indexOf(' ');
        return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    }

    private static void spacer(FabricClientCommandSource source) {
        feedback(source, Component.empty());
    }

    private static void feedback(FabricClientCommandSource source, Component message) {
        source.sendFeedback(WaypointerChatFeedback.suppress(message));
    }

    private record HelpRow(String usage, String descriptionKey, List<String> examples) {
        HelpRow(String usage, String descriptionKey, String... examples) {
            this(usage, descriptionKey, List.of(examples));
        }
    }

    private record HelpSection(String id, String titleKey, List<HelpRow> rows) {}
}
