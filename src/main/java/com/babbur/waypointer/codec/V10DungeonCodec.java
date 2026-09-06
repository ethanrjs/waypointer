package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.V10DungeonBodyCodec;

import java.io.IOException;
import java.util.Collection;
import java.util.zip.Deflater;

/** Universal {@code WP:} wire-v10 kind-4 dungeon collection codec. */
final class V10DungeonCodec {

    private V10DungeonCodec() {}

    static String encode(Collection<WaypointGroup> routes) throws IOException {
        byte[] semantic = V10DungeonBodyCodec.encode(routes);
        return WaypointCodec.MAGIC + selectCandidate(semantic).transport();
    }

    static V10Transport.Outbound selectCandidate(byte[] semantic) throws IOException {
        if ((long) semantic.length + V10Transport.CHECKSUM_BYTES > V10Transport.MAX_FRAME_BYTES) {
            throw new V10ProfileLimitException(
                    "v10 dungeon semantic body exceeds the 2 MiB frame profile");
        }
        V10Transport.Outbound best = V10Transport.direct(semantic);
        best = chooseDeflatedIfWithinProfile(best, semantic, Deflater.DEFAULT_STRATEGY);
        best = chooseDeflatedIfWithinProfile(best, semantic, Deflater.FILTERED);
        return best;
    }

    private static V10Transport.Outbound chooseDeflatedIfWithinProfile(
            V10Transport.Outbound best, byte[] semantic, int strategy) throws IOException {
        try {
            V10Transport.Outbound compressed = V10Transport.deflated(semantic, strategy);
            return compressed.compareTo(best) < 0 ? compressed : best;
        } catch (V10ProfileLimitException oversizedCandidate) {
            return best;
        }
    }

    static V10DungeonBodyCodec.Decoded decode(String code) throws IOException {
        if (code == null || !code.startsWith(WaypointCodec.MAGIC)) {
            throw new IOException("v10 dungeon share must start with " + WaypointCodec.MAGIC);
        }
        return decode(V10Transport.probe(code.substring(WaypointCodec.MAGIC.length())));
    }

    static V10DungeonBodyCodec.Decoded decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != V10DungeonBodyCodec.CONTENT_KIND) {
            throw new IOException("expected v10 dungeon kind 4, got kind " + frame.contentKind());
        }
        return V10DungeonBodyCodec.decode(frame.semantic());
    }

}
