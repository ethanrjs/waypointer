package com.babbur.waypointer.dungeon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class DungeonItemIdentity {

    public static final String DUNGEONBREAKER_ID = "DUNGEONBREAKER";
    private static final String ASPECT_OF_THE_END_ID = "ASPECT_OF_THE_END";
    private static final String ASPECT_OF_THE_VOID_ID = "ASPECT_OF_THE_VOID";
    private static final String SUPERBOOM_TNT_ID = "SUPERBOOM_TNT";
    private static final String INFINITE_SUPERBOOM_TNT_ID = "INFINITE_SUPERBOOM_TNT";

    private DungeonItemIdentity() {}

    public static boolean isDungeonbreaker(ItemStack stack) {
        return hasSkyBlockId(stack, DUNGEONBREAKER_ID);
    }

    /**
     * True only for Aspect of the End / Void with Etherwarp merged on
     * ({@code ethermerge}). The standalone Etherwarp Conduit is a different item.
     */
    public static boolean isEtherwarpItem(ItemStack stack) {
        CompoundTag data = customData(stack);
        if (data == null) return false;
        CompoundTag attributes = attributes(data);
        String id = attributes.getStringOr("id", "");
        if (!ASPECT_OF_THE_END_ID.equals(id) && !ASPECT_OF_THE_VOID_ID.equals(id)) return false;
        return attributes.getBoolean("ethermerge").orElse(false)
                || attributes.getInt("ethermerge").orElse(0) == 1;
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
