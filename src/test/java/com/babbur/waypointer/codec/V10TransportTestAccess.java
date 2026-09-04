package com.babbur.waypointer.codec;

import java.io.IOException;
import java.util.zip.Deflater;

/** Test-only bridge so suites outside the codec package can frame raw V10 semantic bodies. */
public final class V10TransportTestAccess {

    private V10TransportTestAccess() {}

    /** Complete {@code WP:} text for a semantic body using the reference three-candidate selection. */
    public static String finalWire(byte[] semantic) throws IOException {
        V10Transport.Outbound best = V10Transport.direct(semantic);
        V10Transport.Outbound defaultDeflate = V10Transport.deflated(
                semantic, Deflater.DEFAULT_STRATEGY);
        if (defaultDeflate.compareTo(best) < 0) best = defaultDeflate;
        V10Transport.Outbound filteredDeflate = V10Transport.deflated(
                semantic, Deflater.FILTERED);
        if (filteredDeflate.compareTo(best) < 0) best = filteredDeflate;
        return WaypointCodec.MAGIC + best.transport();
    }

    /** Transport mode (0 direct, 1 DEFLATE) of a complete {@code WP:} V10 string. */
    public static int mode(String wire) throws IOException {
        return V10Transport.probe(wire.substring(WaypointCodec.MAGIC.length())).mode();
    }
}
