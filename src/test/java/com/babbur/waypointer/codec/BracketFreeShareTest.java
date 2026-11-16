package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BracketFreeShareTest {

    @AfterEach
    void restoreDefaultSpelling() {
        UniversalShareCodec.shareWithoutAngleBracketsWhen(null);
    }

    @Test
    void sharesKeepTheStandardSpellingUnlessThePlayerOptsIn() {
        List<WaypointGroup> routes = List.of(route(3));
        String standard = V11Codec.encodeBest(routes, WaypointCodec.Options.FULL_FIDELITY,
                RouteLibraryMetadata.empty());
        assertEquals(standard, WaypointExportCodec.encode(routes, WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER));

        UniversalShareCodec.shareWithoutAngleBracketsWhen(() -> true);
        String shared = WaypointExportCodec.encode(routes, WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.WAYPOINTER);
        assertFalse(shared.matches(".*[<>].*"), shared);
    }

    @Test
    void base89BlocksRoundTripEveryTailLength() {
        Random random = new Random(0xB89);
        for (int length = 0; length < 200; length++) {
            byte[] input = new byte[length];
            random.nextBytes(input);

            String text = Base89BlockCodec.encode(input);

            assertArrayEquals(input, Base89BlockCodec.decode(text), "length " + length);
            assertFalse(text.contains("<") || text.contains(">"), "length " + length);
        }
    }

    @Test
    void base89BlocksStayCloseToBase91Length() {
        byte[] input = new byte[2100];
        new Random(7).nextBytes(input);

        int base89 = Base89BlockCodec.encode(input).length();
        int base91 = AsciiStreamCodec.encode(input).length();

        assertEquals(2600, base89);
        assertTrue(base89 <= base91 * 1.01, base89 + " vs " + base91);
    }

    @Test
    void base89RejectsImpossibleTailsOverflowAndForeignCharacters() {
        assertThrows(IllegalArgumentException.class, () -> Base89BlockCodec.decode("!"));
        assertThrows(IllegalArgumentException.class, () -> Base89BlockCodec.decode("!!!!!!"));
        assertThrows(IllegalArgumentException.class, () -> Base89BlockCodec.decode("~~"));
        assertThrows(IllegalArgumentException.class, () -> Base89BlockCodec.decode("!<"));
    }

    @Test
    void sharedRoutesHaveNoAngleBracketsAndImportBack() {
        UniversalShareCodec.shareWithoutAngleBracketsWhen(() -> true);
        int respelled = 0;
        for (int seed = 0; seed < 40; seed++) {
            List<WaypointGroup> routes = List.of(route(seed));
            String standard = V11Codec.encodeBest(routes, WaypointCodec.Options.FULL_FIDELITY,
                    RouteLibraryMetadata.empty());
            String shared = WaypointExportCodec.encode(routes, WaypointCodec.Options.FULL_FIDELITY,
                    WaypointExportCodec.Target.WAYPOINTER);

            assertFalse(shared.substring(WaypointCodec.MAGIC.length()).matches(".*[<>].*"), shared);
            if (!shared.equals(standard)) respelled++;
            for (String code : List.of(shared, standard)) {
                WaypointImporter.ImportResult imported = WaypointImporter.importAny(code);
                WaypointGroup back = imported.groups().getFirst();
                assertEquals(routes.getFirst().size(), back.size(), code);
                assertEquals(routes.getFirst().get(3).x(), back.get(3).x(), code);
                assertEquals(routes.getFirst().get(3).name(), back.get(3).name(), code);
            }
        }
        assertTrue(respelled > 30, "most route codes carry an angle bracket in base-91: " + respelled);
    }

    @Test
    void configAndCatalogReferenceSharesAreBracketFreeToo() {
        UniversalShareCodec.shareWithoutAngleBracketsWhen(() -> true);
        WaypointerConfig config = new WaypointerConfig();
        config.setDefaultReachRadius(7.5);
        config.setRouteMaxRenderDistance(321);
        String configCode = UniversalShareCodec.encodeConfig(config);
        assertFalse(configCode.matches(".*[<>].*"), configCode);
        assertEquals(UniversalShareCodec.Type.CONFIG, UniversalShareCodec.decode(configCode).type());

        for (int i = 0; i < 64; i++) {
            String id = "route" + Integer.toString(i * 7919, 36) + "abcdefghijklmnopq".substring(0, 17);
            String reference = UniversalShareCodec.encodeCatalogReference(id);
            assertFalse(reference.matches(".*[<>].*"), reference);
            assertEquals(UniversalShareCodec.Type.CATALOG, UniversalShareCodec.decode(reference).type());
        }
    }

    @Test
    void bracketFreeSpellingIsOnlyCanonicalWhenTheStandardOneHasABracket() throws Exception {
        for (int seed = 0; seed < 40; seed++) {
            String standard = V11Codec.encodeBest(List.of(route(seed)), WaypointCodec.Options.FULL_FIDELITY,
                    RouteLibraryMetadata.empty()).substring(WaypointCodec.MAGIC.length());
            byte[] payload = V10Transport.decode(standard).payload();
            String forced = V10Transport.encodeBracketFree(payload);
            if (V10Transport.hasAngleBracket(standard)) {
                assertArrayEquals(payload, V10Transport.decodeBracketFree(forced).payload());
            } else {
                assertThrows(java.io.IOException.class, () -> V10Transport.decodeBracketFree(forced));
            }
        }
    }

    @Test
    void respellingLeavesNonFrameTextAlone() {
        assertEquals("WP:<not a frame>", UniversalShareCodec.withoutAngleBrackets("WP:<not a frame>"));
        assertEquals("hello <3", UniversalShareCodec.withoutAngleBrackets("hello <3"));
        assertNull(UniversalShareCodec.withoutAngleBrackets(null));
    }

    private static WaypointGroup route(int seed) {
        Random random = new Random(seed * 31L + 5);
        WaypointGroup group = WaypointGroup.create("Route " + seed, "crystal_hollows");
        for (int i = 0; i < 12 + seed; i++) {
            group.add(new Waypoint(512 + random.nextInt(300), 30 + random.nextInt(150),
                    512 + random.nextInt(300), "Point " + i, random.nextInt(0xFFFFFF), 0, 0.0));
        }
        return group;
    }
}
