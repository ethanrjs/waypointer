package com.babbur.waypointer.codec;

import java.io.IOException;

final class ShareSpelling {

    private ShareSpelling() {}

    static String of(String legacyCode) {
        String body = legacyCode.substring(WaypointCodec.MAGIC.length());
        return WaypointCodec.MAGIC + V10Transport.escapeShareText(V10Transport.unescapeContextual(body));
    }

    static String legacy(String code) throws IOException {
        return WaypointCodec.MAGIC + V10Transport.legacyText(code.substring(WaypointCodec.MAGIC.length()));
    }
}
