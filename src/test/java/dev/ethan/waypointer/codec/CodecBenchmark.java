package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Not a pass/fail test; this exists to print realistic codec sizes so we can
 * reason about compression improvements. Run with `--info` to see output.
 */
class CodecBenchmark {

    @Test
    void print_sizes_for_realistic_scenarios() {
        report("Tiny route (3 pts, no names, delta)",       tinyRoute());
        report("Medium route (20 pts, no names, delta)",    mediumRoute(20, false));
        report("Medium route (20 pts, named, delta)",       mediumRoute(20, true));
        report("Long route (50 pts, no names)",             mediumRoute(50, false));
        report("Long route (50 pts, named)",                mediumRoute(50, true));
        report("Huge route (100 pts, no names)",            mediumRoute(100, false));
        report("Dungeon-style (F7 terminals 8 pts named)",  dungeonRoute());
        report("Yo-yo / absolute win (3 pts)",              yoyoRoute());
        report("Random fuzz realistic",                     fuzzRoute(42, 30));
    }

    private static void report(String label, List<WaypointGroup> groups) {
        String named    = WaypointCodec.encode(groups, WaypointCodec.Options.WITH_NAMES);
        String nameless = WaypointCodec.encode(groups, WaypointCodec.Options.NO_NAMES);
        int pts = 0;
        for (WaypointGroup g : groups) pts += g.size();
        System.out.printf("%-48s  pts=%-3d  NAMES=%-4d  NO_NAMES=%-4d%n",
                label, pts, named.length(), nameless.length());
    }

    private static List<WaypointGroup> tinyRoute() {
        WaypointGroup g = WaypointGroup.create("r", "z");
        g.add(Waypoint.at(10, 70, 10));
        g.add(Waypoint.at(15, 71, 13));
        g.add(Waypoint.at(20, 71, 18));
        return List.of(g);
    }

    private static List<WaypointGroup> mediumRoute(int n, boolean names) {
        WaypointGroup g = WaypointGroup.create("medium", "dungeon_f7");
        Random r = new Random(0xBEEF + n);
        int x = 100, y = 64, z = 200;
        for (int i = 0; i < n; i++) {
            x += r.nextInt(11) - 5;
            y = Math.max(1, Math.min(319, y + r.nextInt(5) - 2));
            z += r.nextInt(11) - 5;
            String name = names ? ("pt" + i) : "";
            g.add(new Waypoint(x, y, z, name, Waypoint.DEFAULT_COLOR, 0, 0));
        }
        return List.of(g);
    }

    private static List<WaypointGroup> dungeonRoute() {
        WaypointGroup g = WaypointGroup.create("F7 Terminals", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(new Waypoint( 66, 128, 135, "T1", 0x40E0D0, 0, 0));
        g.add(new Waypoint( 79, 128, 142, "T2", 0xFF55AA, 0, 0));
        g.add(new Waypoint( 89, 132, 140, "T3", 0x55FF55, 0, 0));
        g.add(new Waypoint( 92, 138, 150, "T4", 0xFFAA00, 0, 0));
        g.add(new Waypoint(110, 140, 160, "Lever A", 0x5555FF, 0, 0));
        g.add(new Waypoint(115, 140, 172, "Lever B", 0x5555FF, 0, 0));
        g.add(new Waypoint(120, 140, 180, "Device",  0xAA00AA, 0, 0));
        g.add(new Waypoint(130, 145, 190, "Boss",    0xFF5555, 0, 0));
        return List.of(g);
    }

    private static List<WaypointGroup> yoyoRoute() {
        WaypointGroup g = WaypointGroup.create("yo", "hub");
        g.add(new Waypoint(0, 64, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(1_000_000, 80, 1_000_000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(0, 64, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        return List.of(g);
    }

    private static List<WaypointGroup> fuzzRoute(long seed, int n) {
        Random r = new Random(seed);
        WaypointGroup g = WaypointGroup.create("fuzz", "crystal_hollows");
        int base = 300;
        int y = 80;
        int bz = -400;
        for (int i = 0; i < n; i++) {
            base += r.nextInt(9) - 4;
            y = Math.max(1, Math.min(319, y + r.nextInt(3) - 1));
            bz += r.nextInt(9) - 4;
            String name = r.nextInt(4) == 0 ? ("p" + i) : "";
            int color = Waypoint.DEFAULT_COLOR;
            g.add(new Waypoint(base, y, bz, name, color, 0, 0));
        }
        return new ArrayList<>(List.of(g));
    }
}
