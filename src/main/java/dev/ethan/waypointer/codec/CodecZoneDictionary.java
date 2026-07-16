package dev.ethan.waypointer.codec;

import java.util.HashMap;
import java.util.Map;

/**
 * Tiny built-in dictionary for zone IDs that are common in shared waypoint routes.
 *
 * <p>The coarse island coverage is derived from Skyblocker's
 * {@code de.hysky.skyblocker.utils.Location} enum, which mirrors Hypixel's
 * location IDs and friendly names:
 * https://github.com/SkyblockerMod/Skyblocker/blob/master/src/main/java/de/hysky/skyblocker/utils/Location.java
 *
 * <p>Waypointer stores a few friendlier canonical IDs (for example
 * {@code the_park} instead of Skyblocker's raw {@code foraging_1}), so this
 * dictionary includes those aliases plus Waypointer-only floor/sub-area IDs.
 * Changing this table changes the wire format: bump {@link WaypointCodec}'s
 * wire version if any entry is added, removed, or reordered.
 */
public final class CodecZoneDictionary {

    private static final String[] IDS = {
            "hub",
            "private_island",
            "dungeon_hub",
            "the_park",
            "the_farming_isles",
            "spiders_den",
            "the_end",
            "crimson_isle",
            "kuudra",
            "gold_mine",
            "deep_caverns",
            "dwarven_mines",
            "crystal_hollows",
            "garden",
            "rift",
            "galatea",
            "backwater_bayou",
            "winter",
            "dark_auction",
            "dungeon",
            "dungeon_f1",
            "dungeon_f2",
            "dungeon_f3",
            "dungeon_f4",
            "dungeon_f5",
            "dungeon_f6",
            "dungeon_f7",
            "dungeon_m1",
            "dungeon_m2",
            "dungeon_m3",
            "dungeon_m4",
            "dungeon_m5",
            "dungeon_m6",
            "dungeon_m7",

            // Raw Skyblocker/Hypixel location IDs whose Waypointer storage IDs differ.
            "dynamic",
            "farming_1",
            "foraging_1",
            "foraging_2",
            "combat_1",
            "combat_2",
            "combat_3",
            "mining_1",
            "mining_2",
            "mining_3",
            "fishing_1",
            "mineshaft",

            // Waypointer refinements beyond Skyblocker's coarse Location enum.
            "great_glacite_lake",
            "glacite_tunnels",
            "dwarven_base_camp",
            "mineshaft_unknown",
            "mineshaft_topaz_1",
            "mineshaft_topaz_2",
            "mineshaft_sapphire_1",
            "mineshaft_sapphire_2",
            "mineshaft_amethyst_1",
            "mineshaft_amethyst_2",
            "mineshaft_amber_1",
            "mineshaft_amber_2",
            "mineshaft_jade_1",
            "mineshaft_jade_2",
            "mineshaft_ruby_1",
            "mineshaft_ruby_2",
            "mineshaft_ruby_crystal",
            "mineshaft_onyx_1",
            "mineshaft_onyx_2",
            "mineshaft_onyx_crystal",
            "mineshaft_aquamarine_1",
            "mineshaft_aquamarine_2",
            "mineshaft_aquamarine_crystal",
            "mineshaft_citrine_1",
            "mineshaft_citrine_2",
            "mineshaft_citrine_crystal",
            "mineshaft_peridot_1",
            "mineshaft_peridot_2",
            "mineshaft_peridot_crystal",
            "mineshaft_jasper",
            "mineshaft_jasper_crystal",
            "mineshaft_opal",
            "mineshaft_opal_crystal",
            "mineshaft_titanium",
            "mineshaft_umber",
            "mineshaft_tungsten",
            "mineshaft_vanguard",
            "mineshaft_littlefoots_den",
            "mineshaft_crystal"
    };

    private static final Map<String, Integer> INDEX_BY_ID = new HashMap<>();

    static {
        for (int i = 0; i < IDS.length; i++) {
            String previous = IDS[i] == null ? null : IDS[i].intern();
            if (previous == null || previous.isEmpty()) {
                throw new IllegalStateException("blank zone dictionary entry at " + i);
            }
            Integer old = INDEX_BY_ID.put(previous, i);
            if (old != null) {
                throw new IllegalStateException("duplicate zone dictionary entry: " + previous);
            }
            IDS[i] = previous;
        }
    }

    private CodecZoneDictionary() {}

    public static int indexOf(String id) {
        Integer index = INDEX_BY_ID.get(id == null ? "" : id);
        return index == null ? -1 : index;
    }

    public static String idAt(int index) {
        if (index < 0 || index >= IDS.length) {
            throw new IllegalArgumentException("zone dictionary OOB: " + index);
        }
        return IDS[index];
    }
}
