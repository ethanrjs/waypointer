package com.babbur.waypointer.dungeon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Exact Hypixel item identity checks shared by dungeon action detectors. */
public final class DungeonItemIdentity {

    public static final String DUNGEONBREAKER_ID = "DUNGEONBREAKER";
    private static final String ETHERWARP_CONDUIT_ID = "ETHERWARP_CONDUIT";
    private static final String SUPERBOOM_TNT_ID = "SUPERBOOM_TNT";
    private static final String INFINITE_SUPERBOOM_TNT_ID = "INFINITE_SUPERBOOM_TNT";

    private DungeonItemIdentity() {}

    public static boolean isDungeonbreaker(ItemStack stack) {
        return hasSkyBlockId(stack, DUNGEONBREAKER_ID);
    }

    /**
     * Etherwarp is available on an Ethermerged item or the conduit itself.
     * Display names are deliberately ignored: renamed shovels must not arm the detector.
     */
    public static boolean isEtherwarpItem(ItemStack stack) {
        CompoundTag data = customData(stack);
        if (data == null) return false;
        CompoundTag attributes = attributes(data);
        return attributes.getInt("ethermerge").orElse(0) == 1
                || ETHERWARP_CONDUIT_ID.equals(attributes.getStringOr("id", ""));
    }

    public static boolean isSuperboom(ItemStack stack) {
        CompoundTag data = customData(stack);
        if (data == null) return false;
        String id = attributes(data).getStringOr("id", "");
        return SUPERBOOM_TNT_ID.equals(id) || INFINITE_SUPERBOOM_TNT_ID.equals(id);
    }

    static boolean hasSkyBlockId(ItemStack stack, String expectedId) {
        CompoundTag data = customData(stack);
        return data != null && hasSkyBlockId(data, expectedId);
    }

    public static boolean hasSkyBlockId(CompoundTag data, String expectedId) {
        if (data == null || data.isEmpty() || expectedId == null) return false;
        return expectedId.equals(attributes(data).getStringOr("id", ""));
    }

    private static CompoundTag attributes(CompoundTag data) {
        CompoundTag nested = data.getCompoundOrEmpty("ExtraAttributes");
        return nested.isEmpty() ? data : nested;
    }

    private static CompoundTag customData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null || customData.isEmpty() ? null : customData.copyTag();
    }
}
