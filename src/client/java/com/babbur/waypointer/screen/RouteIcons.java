package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.RouteIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class RouteIcons {
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private RouteIcons() {}

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

    static List<Item> allItems() {
        LinkedHashSet<Item> items = new LinkedHashSet<>();
        var level = Minecraft.getInstance().level;
        if (level != null) {
            try {
                CreativeModeTabs.tryRebuildTabContents(
                        level.enabledFeatures(), false, level.registryAccess());
                List<CreativeModeTab> tabs = new ArrayList<>();
                for (var tab : CreativeModeTabs.tabs()) {
                    if (tab.getType() == CreativeModeTab.Type.CATEGORY) tabs.add(tab);
                }
                tabs.sort(Comparator.comparingInt(RouteIcons::tabRank));
                for (var tab : tabs) {
                    for (ItemStack stack : tab.getDisplayItems()) items.add(stack.getItem());
                }
            } catch (RuntimeException ignored) {
            }
        }
        for (Item item : BuiltInRegistries.ITEM) items.add(item);
        items.remove(Items.AIR);
        return new ArrayList<>(items);
    }

    private static final List<String> TAB_ORDER = List.of("tools_and_utilities", "ingredients",
            "natural_blocks", "combat", "food_and_drinks", "functional_blocks", "redstone_blocks",
            "colored_blocks", "building_blocks", "spawn_eggs");

    private static int tabRank(CreativeModeTab tab) {
        Identifier key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        int rank = key == null ? -1 : TAB_ORDER.indexOf(key.getPath());
        return rank < 0 ? TAB_ORDER.size() : rank;
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

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

    static void slot(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0x50000000);
        g.fill(x, y, x + size, y + 1, 0x70000000);
        g.fill(x, y + 1, x + 1, y + size, 0x70000000);
        g.fill(x + 1, y + size - 1, x + size, y + size, 0x14FFFFFF);
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, 0x14FFFFFF);
    }

    static void slotted(GuiGraphicsExtractor g, String spec, int x, int y, int size, int itemSize) {
        slot(g, x, y, size);
        int inset = (size - itemSize) / 2;
        draw(g, spec, x + inset, y + inset, itemSize);
    }
}
