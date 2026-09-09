package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.core.*;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import com.babbur.waypointer.screen.preview.WaypointInspectorWidget;
import com.babbur.waypointer.screen.preview.WaypointAppearancePreviewPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Transactional appearance editor, shared by defaults, routes and selected points. */
public final class WaypointAppearanceScreen extends Screen {
    private record Section(String name, List<Setting> settings) {}
    private final Screen parent;
    private WaypointerConfig inherited;
    private WaypointerConfig routeConfig;
    private String selectedPreset;
    private int previewColor;
    private boolean pointScope;
    private boolean selectStaticPreview;
    private WaypointInspectorWidget inspector;
    private final Consumer<WaypointStyle> apply;
    private final WaypointGroup group;
    private final Waypoint waypoint;
    private final boolean defaults;
    private final List<Section> sections = new ArrayList<>();
    private final Set<String> touched = new HashSet<>();
    private final Set<String> invalid = new HashSet<>();
    private final Map<String, String> draftText = new HashMap<>();
    private WaypointStyle style;
    private WaypointerConfig draft;
    private int sectionIndex, page;
    private boolean previewOnly, sectionOverview = true;
    private Button applyButton;
    private final Map<Button, Section> previewSections = new LinkedHashMap<>();
    private Section lastPreviewSection;

    private WaypointAppearanceScreen(Screen parent, WaypointerConfig inherited, WaypointStyle style,
            Consumer<WaypointStyle> apply, String title, WaypointGroup group, Waypoint waypoint, boolean defaults) {
        super(Component.literal(title));
        this.parent = parent; this.inherited = inherited; this.style = style; this.apply = apply;
        this.group = group; this.waypoint = waypoint; this.defaults = defaults;
        this.previewColor = waypoint == null ? inherited.defaultWaypointColor() : waypoint.color();
        draft = WaypointStyles.preview(inherited, style);
        for (var category : SettingsCatalog.categories()) for (var section : SettingsScreen.sections(category)) {
            List<Setting> settings = section.groups().stream().flatMap(g -> g.settings().stream())
                    .filter(s -> WaypointStyles.supportedProperties().contains(s.id()))
                    .filter(s -> s.kind() != Setting.Kind.HIDDEN && s.kind() != Setting.Kind.ACTION).toList();
            String categoryName = Component.translatableWithFallback(SettingsCatalog.categoryTranslationKey(category), category.label()).getString();
            String name = section.heading() == null || section.heading().label() == null ? categoryName
                    : Component.translatableWithFallback(SettingsCatalog.groupTranslationKey(category, section.heading()), section.heading().label()).getString();
            if (settings.size() == 1 && settings.get(0).id().equals("maxStaticWaypointRenderDistance"))
                name = text("section.static_distance", "Static distance").getString();
            else if (settings.stream().anyMatch(setting -> setting.id().equals("boxStyle")))
                name = text("section.marker", "Marker").getString();
            else if (settings.stream().anyMatch(setting -> setting.id().equals("currentWaypointOpacity")))
                name = text("section.sequence", "Sequence states").getString();
            if (!settings.isEmpty()) sections.add(new Section(name, settings));
        }
    }

    public static void openGlobal(Screen parent, WaypointerConfig config) {
        show(new WaypointAppearanceScreen(parent, config, WaypointStyles.capture(config),
                style -> WaypointStyles.applyTo(config, style), text("defaults", "Default appearance").getString(), null, null, true));
    }
    public static void openStyle(Screen parent, WaypointerConfig base, WaypointStyle initial,
            Consumer<WaypointStyle> apply, String title) {
        show(new WaypointAppearanceScreen(parent, base, initial, apply, title, null, null, false));
    }
    public static void openRoute(Screen parent, WaypointerConfig config, ActiveGroupManager manager, WaypointGroup group) {
        WaypointGroup inheritedGroup = new WaypointGroup(group.id(), group.name(), group.zoneId());
        inheritedGroup.setStylePresetId(group.stylePresetId());
        WaypointAppearanceScreen[] editor = new WaypointAppearanceScreen[1];
        editor[0] = new WaypointAppearanceScreen(parent, WaypointStyles.resolve(config, inheritedGroup, null), group.styleOverride(),
                style -> { group.setStylePresetId(editor[0].selectedPreset); group.setStyleOverride(style); manager.fireDataChanged(); },
                text("route", "Route appearance: %s", group.name()).getString(), group, group.size() == 0 ? null : group.get(0), false);
        if (editor[0].waypoint != null) editor[0].previewColor = WaypointStyles.waypointColor(config, inheritedGroup, editor[0].waypoint.withStyleOverride(WaypointStyle.EMPTY));
        editor[0].routeConfig = config;
        editor[0].selectedPreset = group.stylePresetId();
        show(editor[0]);
    }
    public static void openPoints(Screen parent, WaypointerConfig config, ActiveGroupManager manager,
            WaypointGroup group, List<Integer> indices, Runnable applied) {
        List<Waypoint> points = indices.stream().filter(i -> i >= 0 && i < group.size()).distinct().map(group::get).toList();
        if (points.isEmpty()) return;
        WaypointStyle common = points.get(0).styleOverride();
        for (Waypoint point : points) for (String key : List.copyOf(common.values().keySet()))
            if (!Objects.equals(common.values().get(key), point.styleOverride().values().get(key))) common = common.without(key);
        WaypointAppearanceScreen[] editor = new WaypointAppearanceScreen[1];
        editor[0] = new WaypointAppearanceScreen(parent, WaypointStyles.resolve(config, group, null), common, style -> {
            for (Waypoint point : points) {
                int index = identityIndex(group, point);
                if (index < 0) continue;
                WaypointStyle next = point.styleOverride();
                for (String key : editor[0].touched) next = next.with(key, style.values().get(key));
                group.setWaypointStyle(index, next);
            }
            manager.fireDataChanged(); applied.run();
        }, points.size() == 1 ? text("waypoint", "Waypoint appearance").getString() : text("selected", "Appearance: %s selected points", points.size()).getString(), group, points.get(0), false);
        editor[0].pointScope = true;
        editor[0].previewColor = WaypointStyles.waypointColor(config, group, points.get(0).withStyleOverride(WaypointStyle.EMPTY));
        show(editor[0]);
    }
    static int identityIndex(WaypointGroup group, Waypoint point) {
        for (int i = 0; i < group.size(); i++) if (group.get(i) == point) return i;
        return -1;
    }
    private static void show(Screen screen) { MinecraftCompat.setScreen(Minecraft.getInstance(), screen); }
    @Override protected void init() {
        previewSections.clear();
        int controlsWidth = width >= 620 ? width / 2 - 24 : width - 32;
        boolean showPreview = width >= 620 || previewOnly;
        Button sectionButton = button(sectionOverview ? text("sections", "Appearance sections") : Component.literal(sections.get(sectionIndex).name() + " >"), b -> {
            sectionOverview = !sectionOverview; page = 0; rebuildWidgets();
        }, 16, 38, Math.max(80, controlsWidth - 90));
        if (!sectionOverview) previewSections.put(sectionButton, sections.get(sectionIndex));
        if (width < 620) button(previewOnly ? text("controls", "Controls") : text("preview", "Preview"), b -> {
            previewOnly = !previewOnly; rebuildWidgets();
        }, width - 100, 38, 84);
        if (showPreview) {
            int x = width >= 620 ? width / 2 + 8 : 16;
            // Reframe when GUI scaling switches between the wide and compact preview cameras.
            if (inspector != null && (inspector.getHeight() < 180) != (Math.max(40, height - 106) < 180)) {
                inspector.releaseResources(); inspector = null;
            }
            if (inspector == null) inspector = new WaypointInspectorWidget(x, 68, width - x - 16, Math.max(40, height - 106),
                    () -> draft, () -> group, () -> waypoint == null ? null : waypoint.withColor(
                            style.values().containsKey("defaultWaypointColor") ? draft.defaultWaypointColor() : previewColor));
            inspector.setContextAppearance(pointScope ? () -> inherited : null, pointScope ? () -> previewColor : null);
            if (selectStaticPreview) { inspector.selectStaticMode(); selectStaticPreview = false; }
            inspector.setWaypointIndexSupplier(() -> group == null || waypoint == null ? 0 : Math.max(0, identityIndex(group, waypoint)));
            inspector.setX(x); inspector.setY(68); inspector.setWidth(width - x - 16); inspector.setHeight(Math.max(40, height - 106));
            addRenderableWidget(inspector);
        }
        if ((!previewOnly || width >= 620) && sectionOverview) {
            int columns = controlsWidth >= 320 ? 2 : 1;
            int visible = Math.max(1, (height - 130) / 24) * columns;
            int start = page * visible;
            int cellWidth = (controlsWidth - (columns - 1) * 6) / columns;
            for (int i = start; i < Math.min(sections.size(), start + visible); i++) {
                final int selected = i;
                Button categoryButton = button(Component.literal(sections.get(i).name()), b -> {
                    lastPreviewSection = sections.get(selected);
                    selectStaticPreview = lastPreviewSection.settings().stream().anyMatch(setting -> setting.id().equals("maxStaticWaypointRenderDistance"));
                    sectionIndex = selected; sectionOverview = false; page = 0; rebuildWidgets();
                }, 16 + ((i - start) % columns) * (cellWidth + 6), 68 + ((i - start) / columns) * 24, cellWidth);
                previewSections.put(categoryButton, sections.get(i));
            }
            if (sections.size() > visible) {
                button(Component.literal("<"), b -> { page--; rebuildWidgets(); }, 16, height - 58, 30).active = page > 0;
                button(Component.literal(">"), b -> { page++; rebuildWidgets(); }, 50, height - 58, 30).active = start + visible < sections.size();
            }
        } else if (!previewOnly || width >= 620) {
            List<Setting> settings = sections.get(sectionIndex).settings();
            int count = Math.max(1, (height - 126) / 44);
            page = Math.min(page, Math.max(0, (settings.size() - 1) / count));
            int start = page * count;
            for (int i = start; i < Math.min(settings.size(), start + count); i++) addSetting(settings.get(i), 16, 68 + (i - start) * 44, controlsWidth);
            if (settings.size() > count) {
                button(Component.literal("<"), b -> { page--; rebuildWidgets(); }, 16, height - 58, 30).active = page > 0;
                button(Component.literal((page + 1) + " / " + ((settings.size() + count - 1) / count) + " >"), b -> { page++; rebuildWidgets(); }, 50, height - 58, 90).active = start + count < settings.size();
            }
        }
        if (routeConfig != null) {
            String presetName = selectedPreset == null || selectedPreset.isEmpty() ? text("inherit", "Inherit").getString() : selectedPreset;
            button(text("preset", "Style: %s", presetName), b -> {
                List<String> choices = new ArrayList<>(); choices.add(""); choices.addAll(routeConfig.namedStyles().keySet());
                selectedPreset = choices.get((choices.indexOf(selectedPreset) + 1) % choices.size());
                WaypointGroup inheritedGroup = new WaypointGroup(group.id(), group.name(), group.zoneId());
                inheritedGroup.setStylePresetId(selectedPreset);
                inherited = WaypointStyles.resolve(routeConfig, inheritedGroup, null);
                if (waypoint != null) previewColor = WaypointStyles.waypointColor(routeConfig, inheritedGroup, waypoint.withStyleOverride(WaypointStyle.EMPTY));
                draft = WaypointStyles.preview(inherited, style); rebuildWidgets();
            }, 16, height - 28, Math.max(60, Math.min(220, width - 212)));
        }
        applyButton = button(text("apply", "Apply"), b -> { if (invalid.isEmpty()) { apply.accept(style); onClose(); } }, width - 180, height - 28, 78);
        refreshApplyState();
        button(text("cancel", "Cancel"), b -> onClose(), width - 94, height - 28, 78);
    }
    private static Component text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("waypointer.appearance." + key, fallback, args);
    }
    private Button button(Component label, Button.OnPress action, int x, int y, int w) {
        return addRenderableWidget(GuiTokens.styledButton(x, y, w, 20, label, action, null));
    }
    private void addSetting(Setting setting, int x, int y, int w) {
        Object value = setting.get(draft, null);
        int fieldWidth = Math.max(70, w - 78);
        Component label = Component.translatableWithFallback(setting.labelTranslationKey(), setting.label());
        if (setting.kind() == Setting.Kind.BOOL || setting.kind() == Setting.Kind.ENUM) {
            Button control = button(SettingsText.localizedValue(setting, value), b -> {
                Object next = !Boolean.TRUE.equals(value);
                if (setting.kind() == Setting.Kind.ENUM) {
                    List<Setting.EnumOption> options = setting.enumOptions();
                    int index = 0; for (int i = 0; i < options.size(); i++) if (Objects.equals(value, options.get(i).value())) index = i;
                    next = options.get((index + 1) % options.size()).value();
                }
                change(setting, next); rebuildWidgets();
            }, x, y + 13, fieldWidth);
            control.setTooltip(Tooltip.create(Component.translatableWithFallback(setting.tooltipTranslationKey(), setting.tooltip())));
        } else {
            EditBox field = new EditBox(font, x, y + 13, fieldWidth, 20, label);
            field.setMaxLength(128);
            field.setValue(draftText.getOrDefault(setting.id(), setting.kind() == Setting.Kind.NUMBER ? String.valueOf(value) : setting.formatValue(value)));
            if (invalid.contains(setting.id())) field.setTextColor(0xFFFF7777);
            field.setResponder(text -> {
                draftText.put(setting.id(), text);
                try {
                    Object next = text;
                    if (setting.kind() == Setting.Kind.NUMBER) {
                        double number = Double.parseDouble(text);
                        if (!setting.acceptsNumber(number)) throw new IllegalArgumentException();
                        next = number;
                    } else if (setting.kind() == Setting.Kind.COLOR) {
                        String hex = text.startsWith("#") ? text.substring(1) : text;
                        if (!hex.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
                        next = Integer.parseInt(hex, 16);
                    } else if (!setting.acceptsText(text)) throw new IllegalArgumentException();
                    change(setting, next); invalid.remove(setting.id()); field.setTextColor(0xFFFFFFFF);
                } catch (IllegalArgumentException ex) { invalid.add(setting.id()); field.setTextColor(0xFFFF7777); }
                refreshApplyState();
            });
            field.setTooltip(Tooltip.create(Component.translatableWithFallback(setting.tooltipTranslationKey(), setting.tooltip())));
            addRenderableWidget(field);
        }
        button(defaults ? text("reset", "Reset") : text("inherit", "Inherit"), b -> {
            touched.add(setting.id()); invalid.remove(setting.id()); draftText.remove(setting.id());
            style = defaults ? style.with(setting.id(), WaypointStyles.capture(new WaypointerConfig()).values().get(setting.id())) : style.without(setting.id());
            draft = WaypointStyles.preview(inherited, style); rebuildWidgets();
        }, x + fieldWidth + 5, y + 13, 73).active = !defaults || style.values().containsKey(setting.id());
    }
    private void refreshApplyState() {
        if (applyButton == null) return;
        applyButton.active = invalid.isEmpty();
        String fields = invalid.stream().sorted().map(SettingsCatalog::byId).filter(Objects::nonNull)
                .map(setting -> SettingsText.label(setting).getString()).collect(java.util.stream.Collectors.joining(", "));
        applyButton.setTooltip(invalid.isEmpty() ? null : Tooltip.create(text("invalid", "Correct invalid values: %s", fields)));
    }
    private void change(Setting setting, Object value) {
        setting.set(draft, null, value); touched.add(setting.id()); style = style.with(setting.id(), WaypointStyles.capture(draft).values().get(setting.id()));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Section pointed = null;
        for (var entry : previewSections.entrySet()) if (entry.getKey().isMouseOver(mouseX, mouseY)) { pointed = entry.getValue(); break; }
        if (pointed == null) for (var entry : previewSections.entrySet()) if (entry.getKey().isFocused()) { pointed = entry.getValue(); break; }
        if (pointed != null) lastPreviewSection = pointed;
        Section spotlight = width < 620 && previewOnly ? lastPreviewSection : pointed;
        if (inspector != null) inspector.setHighlight(spotlight == null ? null
                : WaypointAppearancePreviewPart.forSettings(spotlight.settings().stream().map(Setting::id).toList()),
                spotlight == null ? Component.empty() : Component.literal(spotlight.name()));
        g.fill(0, 0, width, height, GuiTokens.SURFACE);
        g.text(font, getTitle(), 16, 16, GuiTokens.TEXT, false);
        if ((!previewOnly || width >= 620) && !sectionOverview) {
            int count = Math.max(1, (height - 126) / 44), start = page * count;
            List<Setting> settings = sections.get(sectionIndex).settings();
            int max = width >= 620 ? width / 2 - 24 : width - 32;
            for (int i = start; i < Math.min(settings.size(), start + count); i++) {
                Setting s = settings.get(i);
                String label = Component.translatableWithFallback(s.labelTranslationKey(), s.label()).getString();
                g.text(font, font.plainSubstrByWidth(label, max), 16, 68 + (i - start) * 44, GuiTokens.TEXT_DIM, false);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }
    @Override public void removed() { if (inspector != null) { inspector.releaseResources(); inspector = null; } }
    @Override public void onClose() { show(parent); }
}
