package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.V10ConfigBodyCodec;
import com.babbur.waypointer.config.WaypointerConfig;

import java.io.IOException;
import java.util.zip.Deflater;

/** Universal {@code WP:} wire-v10 kind-3 configuration codec. */
final class V10ConfigCodec {

    private V10ConfigCodec() {}

    static String encode(WaypointerConfig config) throws IOException {
        byte[] semantic = V10ConfigBodyCodec.encode(config);
        byte[] directSealed = V10Transport.seal(V10Transport.MODE_DIRECT, semantic);
        Candidate best = new Candidate(V10Transport.MODE_DIRECT, directSealed);

        Candidate defaultDeflate = new Candidate(V10Transport.MODE_DEFLATE,
                V10Transport.deflateAndSeal(semantic, Deflater.DEFAULT_STRATEGY));
        if (defaultDeflate.compareTo(best) < 0) best = defaultDeflate;
        Candidate filteredDeflate = new Candidate(V10Transport.MODE_DEFLATE,
                V10Transport.deflateAndSeal(semantic, Deflater.FILTERED));
        if (filteredDeflate.compareTo(best) < 0) best = filteredDeflate;
        return WaypointCodec.MAGIC + best.transport;
    }

    static WaypointerConfig decode(String code) throws IOException {
        if (code == null || !code.startsWith(WaypointCodec.MAGIC)) {
            throw new IOException("v10 config must start with " + WaypointCodec.MAGIC);
        }
        return decode(V10Transport.probe(code.substring(WaypointCodec.MAGIC.length())));
    }

    static WaypointerConfig decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != V10ConfigBodyCodec.CONTENT_KIND) {
            throw new IOException("expected v10 config kind 3, got kind " + frame.contentKind());
        }
        return V10ConfigBodyCodec.decode(frame.semantic());
    }

    private static final class Candidate implements Comparable<Candidate> {
        private final int mode;
        private final byte[] payload;
        private final String transport;

        Candidate(int mode, byte[] payload) {
            this.mode = mode;
            this.payload = payload;
            this.transport = V10Transport.encode(mode, payload);
        }

        @Override
        public int compareTo(Candidate other) {
            int compared = Integer.compare(transport.length(), other.transport.length());
            if (compared != 0) return compared;
            compared = Integer.compare(mode, other.mode);
            if (compared != 0) return compared;
            compared = Integer.compare(payload.length, other.payload.length);
            if (compared != 0) return compared;
            for (int index = 0; index < payload.length; index++) {
                compared = Integer.compare(payload[index] & 0xFF, other.payload[index] & 0xFF);
                if (compared != 0) return compared;
            }
            return 0;
        }
    }
}
