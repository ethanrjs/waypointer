package com.babbur.waypointer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteIconTest {

    @Test
    void normalizesTypedAndStoredIcons() {
        assertEquals("minecraft:diamond_pickaxe", RouteIcon.normalize("Diamond_Pickaxe"));
        assertEquals("minecraft:diamond_pickaxe*", RouteIcon.normalize(" minecraft:diamond_pickaxe* "));
        assertEquals("somemod:gem/ruby", RouteIcon.normalize("somemod:gem/ruby"));
        assertEquals(RouteIcon.NONE, RouteIcon.normalize("none"));
        assertEquals(RouteIcon.NONE, RouteIcon.normalize("not an item"));
        assertEquals(RouteIcon.NONE, RouteIcon.normalize("minecraft:" + "x".repeat(100)));
        assertEquals(RouteIcon.NONE, RouteIcon.normalize(null));
    }

    @Test
    void shimmerIsATrailingMark() {
        String spec = RouteIcon.of("prismarine_shard", true);
        assertEquals("minecraft:prismarine_shard*", spec);
        assertTrue(RouteIcon.glint(spec));
        assertEquals("minecraft:prismarine_shard", RouteIcon.itemId(spec));
        assertFalse(RouteIcon.glint(RouteIcon.of("prismarine_shard", false)));
    }

    @Test
    void routesWithoutAnIconShowTheirZone() {
        assertEquals("minecraft:iron_pickaxe", RouteIcon.display("", "dwarven_mines"));
        assertEquals("minecraft:wither_skeleton_skull", RouteIcon.display(null, "dungeon_f7"));
        assertEquals("minecraft:packed_ice", RouteIcon.display("", "mineshaft_topaz_1"));
        assertEquals("minecraft:amethyst_shard",
                RouteIcon.display("", "crystal_hollows_structure_mines_of_divan"));
        assertEquals("minecraft:compass", RouteIcon.display("", "private_world"));
        assertEquals("minecraft:emerald", RouteIcon.display("emerald", "dwarven_mines"));
    }

    @Test
    void routesKeepTheirIconThroughCopies() {
        WaypointGroup route = WaypointGroup.create("Ruby", "crystal_hollows");
        route.setIcon("red_stained_glass");
        assertEquals("minecraft:red_stained_glass", route.exportSnapshot().icon());
        route.setIcon("bad value!");
        assertEquals(RouteIcon.NONE, route.icon());
    }
}
