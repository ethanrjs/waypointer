package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

class ShareTuningTest {

    static List<List<WaypointGroup>> rooms;
    static List<List<WaypointGroup>> pack;
    static List<List<WaypointGroup>> corpus;

    static void load() throws Exception {
        if (rooms != null) return;
        List<WaypointGroup> all = DungeonRoomShareCodec.decode(
                Files.readString(Path.of(System.getProperty("share.dungeons")))).routes();
        rooms = all.stream().map(g -> V11Codec.project(List.of(g), WaypointCodec.Options.FULL_FIDELITY)).toList();
        pack = List.of(V11Codec.project(all, WaypointCodec.Options.FULL_FIDELITY));
        corpus = ShareSizeBenchmarkTest.loadCorpus(Path.of(System.getProperty("share.corpus"))).stream()
                .map(g -> V11Codec.project(List.of(g), WaypointCodec.Options.FULL_FIDELITY)).toList();
    }

    static long size(List<List<WaypointGroup>> shares) throws Exception {
        long total = 0;
        for (List<WaypointGroup> share : shares) total += V11CompactCodec.encode(share, "", List.of()).length;
        return total;
    }

    static String measure() throws Exception {
        return String.format(Locale.ROOT, "rooms=%d pack=%d corpus=%d", size(rooms), size(pack), size(corpus));
    }

    static void compareBare() throws Exception {
        List<WaypointGroup> raw = ShareSizeBenchmarkTest.loadCorpus(Path.of(System.getProperty("share.corpus")));
        for (WaypointCodec.Options options : List.of(WaypointCodec.Options.BARE_COORDINATES,
                WaypointCodec.Options.FULL_FIDELITY)) {
            long v10 = 0, v11 = 0, v10Wins = 0, v10Time = 0, v11Time = 0;
            for (WaypointGroup group : raw) {
                List<WaypointGroup> share = List.of(group);
                long t0 = System.nanoTime();
                String a = RouteLibraryCodec.encode(share, options, RouteLibraryMetadata.capture(null, share));
                long t1 = System.nanoTime();
                List<WaypointGroup> projected = V11Codec.project(share, options);
                String b = WaypointCodec.MAGIC + V11Codec.frame(V11CompactCodec.encode(projected,
                        WaypointCodec.Options.sanitizeLabel(options.label), List.of()));
                long t2 = System.nanoTime();
                v10 += a.length();
                v11 += b.length();
                if (a.length() < b.length()) v10Wins++;
                v10Time += t1 - t0;
                v11Time += t2 - t1;
            }
            System.out.println("TUNE " + (options == WaypointCodec.Options.BARE_COORDINATES ? "bare" : "full")
                    + " v10=" + v10 + " v11=" + v11 + " v10 shorter on " + v10Wins + "/" + raw.size()
                    + " encode ms v10=" + v10Time / 1_000_000 + " v11=" + v11Time / 1_000_000);
        }
    }

    static void crossValidated() throws Exception {
        V11Model shipped = V11CompactCodec.model;
        long[] totals = new long[2];
        try {
            for (int half = 0; half < 2; half++) {
                List<com.google.gson.JsonObject> training = new java.util.ArrayList<>();
                List<List<WaypointGroup>> heldRooms = new java.util.ArrayList<>();
                List<List<WaypointGroup>> heldCorpus = new java.util.ArrayList<>();
                for (int i = 0; i < rooms.size(); i++) {
                    if (i % 2 == half) training.add(V11Codec.toJson(rooms.get(i), "", List.of()));
                    else heldRooms.add(rooms.get(i));
                }
                for (int i = 0; i < corpus.size(); i++) {
                    if (i % 2 == half) training.add(V11Codec.toJson(corpus.get(i), "", List.of()));
                    else heldCorpus.add(corpus.get(i));
                }
                V11CompactCodec.model = V11CompactCodec.train(training);
                totals[0] += size(heldRooms);
                totals[1] += size(heldCorpus);
            }
        } finally {
            V11CompactCodec.model = shipped;
        }
        System.out.println("TUNE held-out (trained on the other half) rooms=" + totals[0] + " corpus=" + totals[1]);
    }

    static void writeModel() throws Exception {
        List<com.google.gson.JsonObject> training = new java.util.ArrayList<>();
        for (List<WaypointGroup> room : rooms) training.add(V11Codec.toJson(room, "", List.of()));
        for (List<WaypointGroup> route : corpus) training.add(V11Codec.toJson(route, "", List.of()));
        V11Model trained = V11CompactCodec.train(training);
        byte[] file = trained.write(V11CompactCodec.contexts(), V11CompactCodec.mixers());
        Files.write(Path.of(System.getProperty("share.train")), file);
        System.out.println("TUNE wrote model " + file.length + " bytes");
    }

    @Test
    @EnabledIfSystemProperty(named = "share.tune", matches = ".+")
    void tune() throws Exception {
        load();
        System.out.println("TUNE baseline " + measure());
        compareBare();
        if (System.getProperty("share.cv") != null) crossValidated();
        if (System.getProperty("share.train") != null) writeModel();
    }
}
