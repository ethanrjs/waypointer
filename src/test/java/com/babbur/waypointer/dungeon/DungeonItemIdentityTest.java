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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonItemIdentityTest {

    @Test
    void etherwarpRequiresMergedAspectOfTheEndOrVoid() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemStack ethermergedVoid = shovelWithData(data("ASPECT_OF_THE_VOID", 1, false));
        ItemStack ethermergedEnd = shovelWithData(data("ASPECT_OF_THE_END", 1, true));
        ItemStack plainAotv = shovelWithData(data("ASPECT_OF_THE_VOID", 0, false));
        ItemStack plainAote = shovelWithData(data("ASPECT_OF_THE_END", 0, false));
        ItemStack conduit = shovelWithData(data("ETHERWARP_CONDUIT", 0, false));
        ItemStack conduitWithFlag = shovelWithData(data("ETHERWARP_CONDUIT", 1, false));
        ItemStack randomWithFlag = shovelWithData(data("HYPERION", 1, false));
        ItemStack renamed = emptyShovel();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Aspect of the Void"));

        assertTrue(DungeonItemIdentity.isEtherwarpItem(ethermergedVoid));
        assertTrue(DungeonItemIdentity.isEtherwarpItem(ethermergedEnd));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(plainAotv));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(plainAote));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(conduit));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(conduitWithFlag));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(randomWithFlag));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(renamed));
        assertFalse(DungeonItemIdentity.isEtherwarpItem(ItemStack.EMPTY));
    }

    @Test
    void etherwarpAbilityRequiresAMergedAspectAndIncludesTuners() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemStack merged = shovelWithData(data(
                "ASPECT_OF_THE_VOID", 1, false, 3));
        ItemStack conduit = shovelWithData(data(
                "ETHERWARP_CONDUIT", 0, false, 99));

        EtherwarpAbility mergedAbility = DungeonItemIdentity
                .etherwarpAbility(merged).orElseThrow();
        assertEquals(60, mergedAbility.range());
        assertTrue(DungeonItemIdentity.etherwarpAbility(conduit).isEmpty());
        assertTrue(DungeonItemIdentity.etherwarpAbility(ItemStack.EMPTY).isEmpty());
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
        return data(id, ethermerge, legacyLayout, 0);
    }

    private static CompoundTag data(
            String id, int ethermerge, boolean legacyLayout, int transmissionTuners) {
        CompoundTag values = new CompoundTag();
        values.putString("id", id);
        values.putInt("ethermerge", ethermerge);
        values.putInt("tuned_transmission", transmissionTuners);
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

    @SuppressWarnings("deprecation")
    private static ItemStack emptyShovel() {
        var holder = Items.DIAMOND_SHOVEL.builtInRegistryHolder();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        return new ItemStack(Items.DIAMOND_SHOVEL);
    }
}
