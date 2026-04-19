package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision logic for the command-send guard. Covers the disconnect-prevention
 * hot path: the guard must (a) pass through anything unrelated untouched,
 * (b) fire when a WP: command would punch the 256-byte ceiling, and
 * (c) report enough context to build a useful chat warning.
 */
class CodecSendGuardTest {

    @Test
    void allows_empty_and_non_codec_commands() {
        assertFalse(CodecSendGuard.inspect(null).shouldBlock());
        assertFalse(CodecSendGuard.inspect("").shouldBlock());
        assertFalse(CodecSendGuard.inspect("pc hello friend").shouldBlock());
        // A command the same length as our ceiling but without WP: must not be
        // policed -- other mods / macros may legitimately send long commands.
        String benign = "say " + "x".repeat(300);
        assertFalse(CodecSendGuard.inspect(benign).shouldBlock(),
                "only WP: codec commands are our concern");
    }

    @Test
    void allows_codec_commands_under_the_byte_ceiling() {
        String small = "pc " + WaypointCodec.MAGIC + "0123456789";
        CodecSendGuard.Decision d = CodecSendGuard.inspect(small);
        assertFalse(d.shouldBlock(),
                "codec commands well below the cap must not be cancelled");
    }

    @Test
    void blocks_codec_command_that_exceeds_the_byte_ceiling() {
        // Build a codec large enough to punch the ceiling even with no
        // command prefix. Using the real encoder proves the guard agrees
        // with the codec's actual on-the-wire representation.
        String encoded = encodeLargeRoute();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length > CodecSendGuard.COMMAND_WIRE_LIMIT_BYTES,
                "test precondition: real encoded route must be over the limit");

        String command = "pc " + encoded;
        int expectedBytes = command.getBytes(StandardCharsets.UTF_8).length;

        CodecSendGuard.Decision d = CodecSendGuard.inspect(command);
        assertTrue(d.shouldBlock(), "oversized WP: command must be cancelled");
        assertEquals(expectedBytes, d.wireBytes(),
                "decision must report the real wire-byte count so the warning is accurate");
        assertEquals(expectedBytes - CodecSendGuard.COMMAND_WIRE_LIMIT_BYTES, d.overByBytes(),
                "overBy must match bytes - cap");
        assertEquals("pc", d.commandLeader(),
                "commandLeader must echo the verb so the warning says which command was blocked");
    }

    @Test
    void reports_leader_for_long_prefix_commands() {
        // /msg <name> <codec> is a legit way to share routes DM-style; when it
        // overflows, the user needs to see "msg" in the warning, not "pc".
        String command = "msg " + "a".repeat(20) + " " + WaypointCodec.MAGIC + "\u4E00".repeat(100);
        CodecSendGuard.Decision d = CodecSendGuard.inspect(command);
        assertTrue(d.shouldBlock());
        assertEquals("msg", d.commandLeader());
    }

    @Test
    void exact_byte_boundary_is_allowed() {
        // Boundary matters: the server accepts <=256, rejects >256. A guard
        // that blocked at the boundary would false-positive on maximally
        // packed exports that are actually safe to send.
        String padded = "a".repeat(CodecSendGuard.COMMAND_WIRE_LIMIT_BYTES - WaypointCodec.MAGIC.length())
                + WaypointCodec.MAGIC;
        assertEquals(CodecSendGuard.COMMAND_WIRE_LIMIT_BYTES,
                padded.getBytes(StandardCharsets.UTF_8).length,
                "test precondition: crafted string must sit exactly at the cap");
        assertFalse(CodecSendGuard.inspect(padded).shouldBlock(),
                "exactly at the ceiling must pass");

        String oneOver = padded + "x";
        assertTrue(CodecSendGuard.inspect(oneOver).shouldBlock(),
                "one byte over the ceiling must block");
    }

    /**
     * Build a codec whose encoded form is guaranteed to exceed the byte cap.
     * Uses waypoints with distinct names so the dictionary-aided DEFLATE has
     * no chance of compressing the payload below the ceiling.
     */
    private static String encodeLargeRoute() {
        WaypointGroup g = WaypointGroup.create("Big Route for Byte-Cap Test", "dungeon_f7");
        List<Waypoint> pts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            pts.add(new Waypoint(100 + i * 7, 60 + (i % 9), 200 - i * 3,
                    "waypt_" + i + "_" + (i * 31 % 97),
                    Waypoint.DEFAULT_COLOR, 0, 0));
        }
        for (Waypoint w : pts) g.add(w);
        return WaypointCodec.encode(List.of(g));
    }
}
