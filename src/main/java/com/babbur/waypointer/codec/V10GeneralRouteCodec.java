package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;

import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;

/** V10 kinds 0 and 7: lossless routes. Kind 7 adds a sender label after the header,
 * leaving header bit 7 for transport mode. */
final class V10GeneralRouteCodec {

    static final int CONTENT_KIND = 0;
    static final int LABELED_CONTENT_KIND = 7;
    static final int SEMANTIC_HEADER = 0x0A;
    static final int LABELED_SEMANTIC_HEADER = 0x7A;

    static boolean isGeneralKind(int contentKind) {
        return contentKind == CONTENT_KIND || contentKind == LABELED_CONTENT_KIND;
    }

    private V10GeneralRouteCodec() {}

    static V10Transport.Outbound encodeCandidate(
            List<WaypointGroup> groups, WaypointCodec.Options options) throws IOException {
        byte[] semantic = WaypointCodec.encodeV10GeneralSemantic(
                groups, options, WaypointCodec.PackingMode.AUTO);
        return selectCandidate(semantic);
    }

    static V10Transport.Outbound selectCandidate(byte[] semantic) throws IOException {
        if ((long) semantic.length + V10Transport.CHECKSUM_BYTES > V10Transport.MAX_FRAME_BYTES) {
            throw new V10ProfileLimitException(
                    "v10 general semantic body exceeds the 2 MiB frame profile");
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

    static WaypointCodec.Decoded decode(V10Transport.CheckedFrame frame) throws IOException {
        if (!isGeneralKind(frame.contentKind())) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        return WaypointCodec.decodeV10GeneralSemantic(frame.semantic());
    }
}
