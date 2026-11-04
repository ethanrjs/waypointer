package com.babbur.waypointer.codec;

import java.nio.charset.StandardCharsets;

public final class CodecDictionary {

    private static final String RAW =

            "dungeon_f7dungeon_f6dungeon_f5dungeon_f4dungeon_f3dungeon_f2dungeon_f1"
          + "dungeon_m7dungeon_m6dungeon_m5dungeon_m4dungeon_m3dungeon_m2dungeon_m1"
          + "dungeon_hub"

          + "crystal_hollowsdwarven_minesthe_farming_islesthe_parkthe_end"
          + "private_islandspiders_dennether_hubkuudrariftmineshaftdeep_caverns"
          + "gold_minegardenhub"

          + "TerminalLeverPuzzleDeviceBossSpawnStartEndCheckpoint"
          + "T1T2T3T4T5T6T7T8";

    public static final byte[] BYTES = RAW.getBytes(StandardCharsets.UTF_8);

    private CodecDictionary() {}
}
