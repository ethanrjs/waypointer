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
                            new HelpRow("", "waypointer.command.help.open_editor", ""),
                            new HelpRow(" help [topic]", "waypointer.command.help.help",
                                    "help", "help waypoints"),
                            new HelpRow(" list", "waypointer.command.help.list_routes", "list"),
                            new HelpRow(" target [route|auto]", "waypointer.command.help.target",
                                    "target", "target 2", "target Ruby route", "target auto"),
                            new HelpRow(" waypoints", "waypointer.command.help.waypoints", "waypoints"),
                            new HelpRow(" settings", "waypointer.command.help.settings", "settings"),
                            new HelpRow(" community", "waypointer.command.help.community", "community"))),
            new HelpSection("routes", "waypointer.command.help.section.routes_target",
                    List.of(
                            new HelpRow(" route new <name>", "waypointer.command.help.route_new",
                                    "route new Fairy souls"),
                            new HelpRow(" route rename <name>", "waypointer.command.help.route_rename_target",
                                    "route rename Ruby route"),
                            new HelpRow(" route mode <step|all>", "waypointer.command.help.route_mode_target",
                                    "route mode step", "route mode all"),
                            new HelpRow(" route radius <blocks>", "waypointer.command.help.route_radius_target",
                                    "route radius 4.5"),
                            new HelpRow(" route skipahead [on|off]", "waypointer.command.help.route_skipahead_target",
                                    "route skipahead off"),
                            new HelpRow(" route colormode <color|gradient|one|paint>",
                                    "waypointer.command.help.route_colormode_target", "route colormode gradient"),
                            new HelpRow(" route color <hex>", "waypointer.command.help.route_color_target",
                                    "route color 4FE05A"),
                            new HelpRow(" route gradient <start> <end>", "waypointer.command.help.route_gradient_target",
                                    "route gradient 00BFFF FF3040"),
                            new HelpRow(" route zone <zone|current>", "waypointer.command.help.route_zone_target",
                                    "route zone current", "route zone the_park"),
                            new HelpRow(" route move <up|down>", "waypointer.command.help.route_move", "route move up"),
                            new HelpRow(" route folder <folder|none>", "waypointer.command.help.route_folder",
                                    "route folder Gemstones", "route folder none"),
                            new HelpRow(" route delete [confirm]", "waypointer.command.help.route_delete_target",
                                    "route delete", "route delete confirm"),
                            new HelpRow(" show <route|all>", "waypointer.command.help.show",
                                    "show 2", "show all"),
                            new HelpRow(" hide [route|all]", "waypointer.command.help.hide",
                                    "hide", "hide 2", "hide all"),
                            new HelpRow(" folder <new|rename|color|delete> ...", "waypointer.command.help.folder",
                                    "folder new Gemstones", "folder rename Gemstones Gems",
                                    "folder color Gems 4FB3C4", "folder delete Gems"),
                            new HelpRow(" edit", "waypointer.command.help.edit", "edit"),
                            new HelpRow(" appearance", "waypointer.command.help.appearance", "appearance"),
                            new HelpRow(" publish", "waypointer.command.help.publish", "publish"),
                            new HelpRow(" clear [confirm]", "waypointer.command.help.clear",
                                    "clear", "clear confirm"))),
            new HelpSection("waypoints", "waypointer.command.help.section.waypoints",
                    List.of(
                            new HelpRow(" add [name]", "waypointer.command.help.add_target",
                                    "add", "add Fairy Soul"),
                            new HelpRow(" add at <x> <y> <z> [name]", "waypointer.command.help.add_at",
                                    "add at 12 70 -4", "add at 12 70 -4 Lever"),
                            new HelpRow(" insert <#> [name]", "waypointer.command.help.insert_before",
                                    "insert 2", "insert 2 Secret"),
                            new HelpRow(" insert <#> at <x> <y> <z> [name]", "waypointer.command.help.insert_at",
                                    "insert 2 at 12 70 -4"),
                            new HelpRow(" remove <#>", "waypointer.command.help.remove_label",
                                    "remove 3", "remove 2.1"),
                            new HelpRow(" move <#> <#>", "waypointer.command.help.move_label", "move 4 2"),
                            new HelpRow(" waypoint <#>", "waypointer.command.help.waypoint_info", "waypoint 3"),
                            new HelpRow(" waypoint <#> rename [name]", "waypointer.command.help.waypoint_rename",
                                    "waypoint 3 rename Lever"),
                            new HelpRow(" waypoint <#> here", "waypointer.command.help.waypoint_move_here",
                                    "waypoint 3 here"),
                            new HelpRow(" waypoint <#> at <x> <y> <z>", "waypointer.command.help.waypoint_move_at",
                                    "waypoint 3 at 12 70 -4"),
                            new HelpRow(" waypoint <#> <up|down>", "waypointer.command.help.waypoint_step",
                                    "waypoint 3 up"),
                            new HelpRow(" waypoint <#> color <hex|reset>", "waypointer.command.help.waypoint_color_reset",
                                    "waypoint 3 color 58C878", "waypoint 3 color reset"),
                            new HelpRow(" waypoint <#> radius <blocks>", "waypointer.command.help.waypoint_radius",
                                    "waypoint 3 radius 1.5"),
                            new HelpRow(" waypoint <#> <enable|disable>", "waypointer.command.help.waypoint_enable",
                                    "waypoint 3 disable"),
                            new HelpRow(" waypoint <#> sub", "waypointer.command.help.waypoint_sub_toggle",
                                    "waypoint 4 sub"),
                            new HelpRow(" waypoint <#> addsub [name]", "waypointer.command.help.waypoint_addsub",
                                    "waypoint 3 addsub Ledge"),
                            new HelpRow(" waypoint <#> flag <flag> [on|off]", "waypointer.command.help.waypoint_flag",
                                    "waypoint 2.1 flag tiny", "waypoint 3 flag line-of-sight off"),
                            new HelpRow(" waypoint <#> type <type>", "waypointer.command.help.waypoint_type",
                                    "waypoint 3 type secret"),
                            new HelpRow(" waypoint <#> appearance", "waypointer.command.help.waypoint_appearance",
                                    "waypoint 3 appearance"))),
            new HelpSection("progress", "waypointer.command.help.section.progress",
                    List.of(
                            new HelpRow(" skip", "waypointer.command.help.skip", "skip"),
                            new HelpRow(" unskip", "waypointer.command.help.unskip", "unskip"),
                            new HelpRow(" skipto <#>", "waypointer.command.help.skipto_target",
                                    "skipto 3", "skipto 3.2"),
                            new HelpRow(" reset", "waypointer.command.help.reset_target", "reset"),
                            new HelpRow(" editmode", "waypointer.command.help.edit_mode", "editmode"))),
            new HelpSection("sharing", "waypointer.command.help.section.sharing",
                    List.of(
                            new HelpRow(" export [routes|bare|names|nonames]", "waypointer.command.help.export_target",
                                    "export", "export routes", "export bare"),
                            new HelpRow(" import [payload]", "waypointer.command.help.import",
                                    "import", "import WP:..."),
                            new HelpRow(" importfile <path>", "waypointer.command.help.importfile",
                                    "importfile C:\\routes\\waypoints.json"),
                            new HelpRow(" importchat <handle>", "waypointer.command.help.importchat",
                                    "importchat A1b2"))),
            new HelpSection("chat", "waypointer.command.help.section.chat",
                    List.of(
                            new HelpRow(" addtemp [at <x> <y> <z> [source]]", "waypointer.command.help.addtemp_here",
                                    "addtemp", "addtemp at 12 70 -4 Party"),
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
                            new HelpRow(" minimap", "waypointer.settings.setting.action.minimapEditor.label", "minimap"),
                            new HelpRow("/wpch add <structure> <x> <y> <z>",
                                    "waypointer.command.help.crystal.add",
                                    "/wpch add odawa 349 110 390")))
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
        if ("wpch".equals(normalized)) return SECTIONS.size() - 1;

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
        }
        // Then a command word, so "help hide" opens the page that explains /wp hide.
        for (int i = 0; i < SECTIONS.size(); i++) {
            for (HelpRow row : SECTIONS.get(i).rows()) {
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
