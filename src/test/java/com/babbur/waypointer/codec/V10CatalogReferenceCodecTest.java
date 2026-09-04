package com.babbur.waypointer.codec;

import com.babbur.waypointer.chat.CodecScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kind 6 subtype 2: a ~30 character code (or a share link) that stands for a published route. */
class V10CatalogReferenceCodecTest {

    /** 22 base64url chars = 16 bytes; the catalog's id shape. */
    private static final String PACKABLE_ID = "Ab3dEfGhIjKlMnOpQrStUw";
    /** Same alphabet, wrong length for packing: must use the inline form. */
    private static final String INLINE_ID = "short-route_id7";

    @Test
    void packed_reference_is_short_and_round_trips_everywhere() throws IOException {
        String code = UniversalShareCodec.encodeCatalogReference(PACKABLE_ID);

        assertTrue(code.startsWith(WaypointCodec.MAGIC));
        assertTrue(code.length() <= 32, "reference must stay tiny, was " + code.length());
        V10Transport.CheckedFrame frame = V10Transport.probe(
                code.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10Transport.MODE_DIRECT, frame.mode());
        assertEquals(6, frame.contentKind());
        assertTrue(V10CatalogReferenceCodec.isReferenceSemantic(frame.semantic()));
        assertEquals(1 + 1 + 1 + 1 + 16, frame.semantic().length);
        assertEquals(PACKABLE_ID, V10CatalogReferenceCodec.decode(frame));

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(code);
        assertEquals(UniversalShareCodec.Type.CATALOG, decoded.type());
        UniversalShareCodec.CatalogReference reference =
                assertInstanceOf(UniversalShareCodec.CatalogReference.class, decoded);
        assertEquals(PACKABLE_ID, reference.routeId());
        assertEquals("https://waypointermod.com/r/" + PACKABLE_ID, reference.shareUrl());

        // Markdown fences and surrounding whitespace are tolerated like other shares.
        assertEquals(PACKABLE_ID, ((UniversalShareCodec.CatalogReference)
                UniversalShareCodec.decode("```\n" + code + "\n```")).routeId());
    }

    @Test
    void inline_form_covers_other_ids_and_packed_is_mandatory_when_possible() throws IOException {
        String inline = UniversalShareCodec.encodeCatalogReference(INLINE_ID);
        V10Transport.CheckedFrame frame = V10Transport.probe(
                inline.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10CatalogReferenceCodec.ID_FORM_INLINE, frame.semantic()[3] & 0xFF);
        assertEquals(INLINE_ID, V10CatalogReferenceCodec.decode(frame));

        // Hand-built inline spelling of a packable id is non-canonical.
        byte[] semantic = V10CatalogReferenceCodec.encodeSemantic(INLINE_ID);
        byte[] ascii = PACKABLE_ID.getBytes();
        byte[] nonCanonical = new byte[4 + 1 + ascii.length];
        System.arraycopy(semantic, 0, nonCanonical, 0, 4);
        nonCanonical[4] = (byte) ascii.length;
        System.arraycopy(ascii, 0, nonCanonical, 5, ascii.length);
        assertRejected(nonCanonical, "non-canonical");

        assertRejected(Arrays.copyOf(V10CatalogReferenceCodec.encodeSemantic(PACKABLE_ID), 10),
                "truncated");
        byte[] trailing = Arrays.copyOf(V10CatalogReferenceCodec.encodeSemantic(PACKABLE_ID), 21);
        assertRejected(trailing, "trailing");
        byte[] reservedCatalog = V10CatalogReferenceCodec.encodeSemantic(PACKABLE_ID);
        reservedCatalog[2] = 1;
        assertRejected(reservedCatalog, "reserved v10 catalog");
        byte[] badForm = V10CatalogReferenceCodec.encodeSemantic(PACKABLE_ID);
        badForm[3] = 7;
        assertRejected(badForm, "route id form");

        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.encodeCatalogReference("has space"));
        assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.encodeCatalogReference(""));
    }

    @Test
    void route_only_decoders_refuse_a_reference_with_a_pointer_to_the_catalog() {
        String code = UniversalShareCodec.encodeCatalogReference(PACKABLE_ID);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(code));
        assertTrue(failure.getMessage().contains("catalog reference"), failure.getMessage());
        assertTrue(failure.getMessage().contains(PACKABLE_ID), failure.getMessage());
        assertFalse(WaypointCodec.isValidCodec(code));
        assertThrows(IllegalArgumentException.class, () -> WaypointImporter.importAny(code));
    }

    @Test
    void share_links_are_the_same_share_as_the_code() {
        for (String link : List.of(
                "https://waypointermod.com/r/" + PACKABLE_ID,
                "http://www.waypointermod.com/r/" + PACKABLE_ID + "/",
                "waypointermod.com/r/" + PACKABLE_ID + "?utm=chat",
                "  https://waypointermod.com/r/" + PACKABLE_ID + "#top  ")) {
            assertEquals(Optional.of(PACKABLE_ID), CatalogShareLink.routeIdFromLink(link), link);
            UniversalShareCodec.CatalogReference reference = assertInstanceOf(
                    UniversalShareCodec.CatalogReference.class, UniversalShareCodec.decode(link));
            assertEquals(PACKABLE_ID, reference.routeId());
        }
        assertEquals(Optional.empty(), CatalogShareLink.routeIdFromLink(
                "https://waypointermod.com/r/" + PACKABLE_ID + "/extra"));
        assertEquals(Optional.empty(), CatalogShareLink.routeIdFromLink(
                "https://waypointermod.com/guides"));
        assertEquals(Optional.empty(), CatalogShareLink.routeIdFromLink(
                "https://example.com/r/" + PACKABLE_ID));
        assertNull(com.babbur.waypointer.screen.CatalogRouteInstallScreen
                .referenceRouteId("not a share at all"));
        assertEquals(PACKABLE_ID, com.babbur.waypointer.screen.CatalogRouteInstallScreen
                .referenceRouteId("https://waypointermod.com/r/" + PACKABLE_ID));
    }

    @Test
    void catalog_install_probe_is_bounded_and_uses_current_client_ids() {
        String code = UniversalShareCodec.encodeCatalogReference(PACKABLE_ID);

        assertEquals(PACKABLE_ID,
                com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(code));
        assertEquals(PACKABLE_ID,
                com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                        "```text\n" + code + "\n```"));
        assertEquals(PACKABLE_ID,
                com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                        "```text\nhttps://waypointermod.com/r/"
                                + PACKABLE_ID + "\n```"));

        // The wire codec accepts this inline spelling, but the catalog detail
        // endpoint currently only addresses its 22-character IDs.
        assertNull(com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                "waypointermod.com/r/short-route_id7"));
        assertNull(com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                UniversalShareCodec.encodeCatalogReference("short-route_id7")));

        // An unrelated clipboard document is rejected before universal import
        // can parse JSON or inflate any payload.
        assertNull(com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                "{\"waypoints\":[]}"));
        assertNull(com.babbur.waypointer.screen.CatalogRouteInstallScreen.referenceRouteId(
                "{\"waypoints\":[\"" + "x".repeat(4_096) + "\"]}"));
    }

    @Test
    void chat_scanner_finds_codes_and_links_as_catalog_pills() {
        String code = UniversalShareCodec.encodeCatalogReference(PACKABLE_ID);
        List<CodecScanner.Match> codeMatches = CodecScanner.scan("try this: " + code + "!");
        assertEquals(1, codeMatches.size());
        assertTrue(codeMatches.getFirst().valid());
        assertEquals(UniversalShareCodec.Type.CATALOG, codeMatches.getFirst().type());
        assertEquals(code, codeMatches.getFirst().text());

        String message = "route here https://waypointermod.com/r/" + PACKABLE_ID
                + ", and another waypointermod.com/r/" + INLINE_ID + ".";
        List<CodecScanner.Match> linkMatches = CodecScanner.scan(message);
        assertEquals(2, linkMatches.size());
        assertEquals("https://waypointermod.com/r/" + PACKABLE_ID, linkMatches.get(0).text());
        assertEquals("waypointermod.com/r/" + INLINE_ID, linkMatches.get(1).text());
        assertTrue(linkMatches.stream().allMatch(match ->
                match.valid() && match.type() == UniversalShareCodec.Type.CATALOG));

        // A link glued to other text is not a share; an unrelated path is ignored.
        assertTrue(CodecScanner.scan("notwaypointermod.com/r/" + PACKABLE_ID).isEmpty());
        assertTrue(CodecScanner.scan("https://waypointermod.com/download").isEmpty());
    }

    private static void assertRejected(byte[] semantic, String expected) {
        IOException failure = assertThrows(IOException.class, () -> V10CatalogReferenceCodec.decode(
                V10Transport.probe(V10Transport.direct(semantic).transport())));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
