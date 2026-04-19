package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the chat-side detection of Waypointer codec strings. The whole point of
 * this feature is that a friend can paste an export into chat and the recipient's
 * client turns it into a clickable import affordance -- misdetections (false
 * positives OR negatives) make the feature useless, so we test edge cases hard.
 */
class CodecScannerTest {

    @Test
    void detects_export_embedded_in_chat_line() {
        String export = sampleExport();
        String message = "Hey, try this route: " + export + " -- boss skip";
        List<CodecScanner.Match> matches = CodecScanner.scan(message);

        assertEquals(1, matches.size(), "should detect exactly one codec in a plain chat line");
        CodecScanner.Match m = matches.get(0);
        assertEquals(export, m.text(),
                "extracted text must match the codec exactly; surrounding chat must not leak in");
    }

    @Test
    void ignores_legacy_prefixes() {
        // Early prototypes tried WPTR1:, WP2:, and WP3: as the magic. None of
        // them shipped. A chat line mentioning any of them must NOT produce a
        // clickable import pill -- the current magic is just WP:, with the
        // version living in the header byte.
        assertTrue(CodecScanner.scan("route: WPTR1:AAAABBBBCCCCDDDD").isEmpty());
        assertTrue(CodecScanner.scan("route: WP2:AAAABBBBCCCCDDDD").isEmpty());
        assertTrue(CodecScanner.scan("route: WP3:AAAABBBBCCCCDDDD").isEmpty());
    }

    @Test
    void extracted_match_decodes_cleanly() {
        String export = sampleExport();
        String chat = "check this -> " + export + " GG";
        CodecScanner.Match m = CodecScanner.scan(chat).get(0);

        // Round-trip through the actual codec to prove the extraction boundary is tight.
        List<WaypointGroup> decoded = WaypointCodec.decode(m.text());
        assertFalse(decoded.isEmpty());
    }

    @Test
    void ignores_bare_magic_without_body() {
        // Don't surface an import button for "just go to WP:" or similar messages --
        // the magic-as-substring case has to be a false positive when no codec
        // body follows. Exercises the MIN_BODY guard specifically, so the strings
        // here use the real current magic (WP:).
        assertTrue(CodecScanner.scan("just go to WP:").isEmpty());
        assertTrue(CodecScanner.scan("WP: ").isEmpty());
        assertTrue(CodecScanner.scan("WP:ab").isEmpty(), "body under MIN_BODY chars must be rejected");
    }

    @Test
    void handles_multiple_codecs_in_one_message() {
        String a = sampleExport();
        String b = sampleExport();
        String chat = "two routes: " + a + " | " + b;

        List<CodecScanner.Match> matches = CodecScanner.scan(chat);
        assertEquals(2, matches.size(), "both codecs should be detected when separated by non-codec text");
    }

    @Test
    void extracts_at_start_and_end_of_message() {
        String export = sampleExport();

        assertEquals(1, CodecScanner.scan(export).size(),
                "codec alone as the whole message must still match");
        assertEquals(1, CodecScanner.scan(export + " trailing").size(),
                "codec at start followed by text must still match");
        assertEquals(1, CodecScanner.scan("leading " + export).size(),
                "codec at end of message must still match");
    }

    @Test
    void empty_and_short_inputs_return_empty() {
        assertTrue(CodecScanner.scan(null).isEmpty());
        assertTrue(CodecScanner.scan("").isEmpty());
        assertTrue(CodecScanner.scan("hello world").isEmpty());
    }

    @Test
    void does_not_match_unrelated_text_containing_magic_substring() {
        // "WP" might appear in any number of unrelated contexts (WordPress, a
        // product name, a URL slug). Without the colon AND a valid body it
        // must not trigger -- the body character class + word-boundary rule
        // keep false positives from being a problem despite the short magic.
        assertTrue(CodecScanner.scan("WP isn't a codec without a colon").isEmpty());
        // NOTE: the 'example' example used to be "example.com/WP" but '.' is
        // no longer an alphabet character (it trips Hypixel's ad filter), so
        // the scanner-level URL-adjacency case is moot. We still exercise the
        // scenario via a non-'.' URL shape below.
        assertTrue(CodecScanner.scan("visit exampleWP").isEmpty());
        assertTrue(CodecScanner.scan("check /wp help for more").isEmpty(),
                "unrelated slash commands that happen to contain 'wp' must not match");
    }

    @Test
    void rejects_magic_mid_word_when_preceding_char_is_ascii_alphanumeric() {
        // v2 uses an ASCII alphabet, so without the word-boundary guard a chat
        // line like "fileWP:stuff" would fire the import pill. The scanner
        // must require the character immediately before the magic to be
        // outside the base-84 alphabet (or be at the start of the string).
        String export = sampleExport();
        assertTrue(CodecScanner.scan("file" + export).isEmpty(),
                "magic preceded by ASCII alphanumeric must not match");
        assertTrue(CodecScanner.scan("helloWP:" + export.substring(3)).isEmpty(),
                "magic glued to a preceding word must not match");
        // Sanity: with a space before (outside the alphabet) the same
        // codec DOES match, so the rule above is doing real work.
        assertEquals(1, CodecScanner.scan("hello " + export).size(),
                "magic with a whitespace boundary must still match");
    }

    private static String sampleExport() {
        WaypointGroup g = WaypointGroup.create("Sample", "hub");
        g.add(new Waypoint(10, 70, 20, "a", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(12, 71, 25, "b", Waypoint.DEFAULT_COLOR, 0, 0));
        return WaypointCodec.encode(List.of(g));
    }
}
