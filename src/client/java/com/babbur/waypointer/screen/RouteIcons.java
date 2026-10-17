package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.RouteIcon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws route icons: Minecraft items as the inventory shows them, in a dark slot. */
final class RouteIcons {
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private RouteIcons() {}

    /** The stack for an icon; an unknown item id (say, from a newer game version) shows a compass. */
    static ItemStack stack(String spec) {
        String key = spec == null ? "" : spec;
        return STACKS.computeIfAbsent(key, RouteIcons::build);
    }

    private static ItemStack build(String spec) {
        Item item = item(RouteIcon.itemId(spec));
        ItemStack stack = new ItemStack(item == null ? Items.COMPASS : item);
        if (RouteIcon.glint(spec)) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    static Item item(String itemId) {
        Identifier id = itemId == null || itemId.isEmpty() ? null : Identifier.tryParse(itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    static boolean known(String spec) {
        return item(RouteIcon.itemId(spec)) != null;
    }

    /**
     * Every item a route can use: in creative inventory order when a world is loaded (so tools sit
     * with tools and ores with ores), then anything the tabs leave out in registry order.
     */
    static List<Item> allItems() {
        java.util.LinkedHashSet<Item> items = new java.util.LinkedHashSet<>();
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            try {
                net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                        level.enabledFeatures(), false, level.registryAccess());
                List<net.minecraft.world.item.CreativeModeTab> tabs = new ArrayList<>();
                for (var tab : net.minecraft.world.item.CreativeModeTabs.tabs()) {
                    if (tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.CATEGORY) tabs.add(tab);
                }
                tabs.sort(java.util.Comparator.comparingInt(RouteIcons::tabRank));
                for (var tab : tabs) {
                    for (ItemStack stack : tab.getDisplayItems()) items.add(stack.getItem());
                }
            } catch (RuntimeException ignored) {
                // Registry order below still lists everything.
            }
        }
        for (Item item : BuiltInRegistries.ITEM) items.add(item);
        items.remove(Items.AIR);
        return new ArrayList<>(items);
    }

    /** Tools, materials and nature suit routes better than building blocks, so they come first. */
    private static final List<String> TAB_ORDER = List.of("tools_and_utilities", "ingredients",
            "natural_blocks", "combat", "food_and_drinks", "functional_blocks", "redstone_blocks",
            "colored_blocks", "building_blocks", "spawn_eggs");

    private static int tabRank(net.minecraft.world.item.CreativeModeTab tab) {
        Identifier key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        int rank = key == null ? -1 : TAB_ORDER.indexOf(key.getPath());
        return rank < 0 ? TAB_ORDER.size() : rank;
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Draws the item with its top-left corner at (x, y), {@code size} pixels square. */
    static void draw(GuiGraphicsExtractor g, String spec, int x, int y, int size) {
        ItemStack stack = stack(spec);
        if (size == 16) {
            g.fakeItem(stack, x, y);
            return;
        }
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(size / 16f, size / 16f);
        g.fakeItem(stack, 0, 0);
        pose.popMatrix();
    }

    /** An inventory slot in the dark theme: sunken edge on the top and left, a faint light edge below. */
    static void slot(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0x50000000);
        g.fill(x, y, x + size, y + 1, 0x70000000);
        g.fill(x, y + 1, x + 1, y + size, 0x70000000);
        g.fill(x + 1, y + size - 1, x + size, y + size, 0x14FFFFFF);
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, 0x14FFFFFF);
    }

    /** A slot with the item centred in it. */
    static void slotted(GuiGraphicsExtractor g, String spec, int x, int y, int size, int itemSize) {
        slot(g, x, y, size);
        int inset = (size - itemSize) / 2;
        draw(g, spec, x + inset, y + inset, itemSize);
    }
}
