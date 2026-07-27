package com.babbur.waypointer.dungeon;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonItemIdentityTest {

    @Test
    void etherwarpRequiresExactCustomDataInsteadOfAName() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemStack ethermerged = shovelWithData(data("ASPECT_OF_THE_VOID", 1, false));
        ItemStack legacyEthermerged = shovelWithData(data("ASPECT_OF_THE_END", 1, true));
        ItemStack conduit = shovelWithData(data("ETHERWARP_CONDUIT", 0, false));
        ItemStack plainAotv = shovelWithData(data("ASPECT_OF_THE_VOID", 0, false));
        ItemStack renamed = emptyShovel();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Aspect of the Void"));

        assertTrue(DungeonItemIdentity.isEtherwarpItem(ethermerged));
        assertTrue(DungeonItemIdentity.isEtherwarpItem(legacyEthermerged));
        assertTrue(DungeonItemIdentity.isEtherwarpItem(conduit));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(plainAotv));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(renamed));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(ItemStack.EMPTY));
    }

    @Test
    void superboomRequiresAnExactSkyBlockIdInsteadOfAName() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemStack finite = shovelWithData(data("SUPERBOOM_TNT", 0, false));
        ItemStack infinite = shovelWithData(data("INFINITE_SUPERBOOM_TNT", 0, true));
        ItemStack renamed = emptyShovel();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Superboom TNT"));

        assertTrue(DungeonItemIdentity.isSuperboom(finite));
        assertTrue(DungeonItemIdentity.isSuperboom(infinite));
        assertFalse(DungeonItemIdentity.isSuperboom(
                shovelWithData(data("SUPERBOOM_TNT_FAKE", 0, false))));
        assertFalse(DungeonItemIdentity.isSuperboom(renamed));
        assertFalse(DungeonItemIdentity.isSuperboom(ItemStack.EMPTY));
    }

    private static CompoundTag data(String id, int ethermerge, boolean legacyLayout) {
        CompoundTag values = new CompoundTag();
        values.putString("id", id);
        values.putInt("ethermerge", ethermerge);
        if (!legacyLayout) return values;
        CompoundTag root = new CompoundTag();
        root.put("ExtraAttributes", values);
        return root;
    }

    private static ItemStack shovelWithData(CompoundTag data) {
        ItemStack stack = emptyShovel();
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private static ItemStack emptyShovel() {
        var holder = Items.DIAMOND_SHOVEL.builtInRegistryHolder();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        return new ItemStack(Items.DIAMOND_SHOVEL);
    }
}
