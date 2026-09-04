package com.babbur.waypointer.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Wire-v10 kind 6, subtype 2: a reference to a route published in the public
 * route catalog. The share carries only the route id; the recipient's client
 * fetches the route from the catalog, so a code of about thirty characters can
 * stand for a route of any size, listed or unlisted.
 *
 * <pre>{@code
 * 0x6A              header
 * subtype : uvarint MUST be 2
 * catalog : uvarint MUST be 0 (waypointermod.com); other values are reserved
 * idForm  : u8      0 = packed, 1 = inline
 * id      : packed: 16 bytes, the base64url decoding of a 22-character route id
 *           inline: length:uvarint (1..64) + ASCII bytes matching [A-Za-z0-9_-]
 * }</pre>
 *
 * <p>The packed form is canonical whenever the id is exactly 22 base64url
 * characters that round-trip through 16 bytes; a decoder rejects the inline
 * spelling of such an id. Nothing follows the id.
 */
final class V10CatalogReferenceCodec {

    static final int CONTENT_KIND = V10BareRoutePackCodec.CONTENT_KIND;
    static final int SEMANTIC_HEADER = V10BareRoutePackCodec.SEMANTIC_HEADER;
    static final int SUBTYPE_CATALOG_REFERENCE = 2;
    static final int CATALOG_WAYPOINTERMOD = 0;
    static final int ID_FORM_PACKED = 0;
    static final int ID_FORM_INLINE = 1;
    static final int PACKED_ID_BYTES = 16;
    static final int PACKED_ID_CHARS = 22;
    static final int MAX_INLINE_ID_CHARS = 64;

    /** Route ids the catalog issues today; the inline form accepts the same alphabet up to 64 chars. */
    static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private V10CatalogReferenceCodec() {}

    /** True when a committed kind-6 semantic body declares the catalog-reference subtype. */
    static boolean isReferenceSemantic(byte[] semantic) {
        return semantic != null && semantic.length >= 2
                && (semantic[0] & 0xFF) == SEMANTIC_HEADER
                && (semantic[1] & 0xFF) == SUBTYPE_CATALOG_REFERENCE;
    }

    static boolean isValidRouteId(String routeId) {
        return routeId != null && ROUTE_ID.matcher(routeId).matches();
    }

    /** Complete transport text (without the {@code WP:} prefix) for a route id. */
    static String encode(String routeId) throws IOException {
        return V10Transport.direct(encodeSemantic(routeId)).transport();
    }

    static byte[] encodeSemantic(String routeId) throws IOException {
        if (!isValidRouteId(routeId)) {
            throw new IllegalArgumentException("catalog route id must match " + ROUTE_ID.pattern());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(24);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(SEMANTIC_HEADER);
        WaypointCodec.writeVarint(out, SUBTYPE_CATALOG_REFERENCE);
        WaypointCodec.writeVarint(out, CATALOG_WAYPOINTERMOD);
        byte[] packed = packedId(routeId);
        if (packed != null) {
            out.writeByte(ID_FORM_PACKED);
            out.write(packed);
        } else {
            byte[] ascii = routeId.getBytes(StandardCharsets.US_ASCII);
            out.writeByte(ID_FORM_INLINE);
            WaypointCodec.writeVarint(out, ascii.length);
            out.write(ascii);
        }
        out.flush();
        return buffer.toByteArray();
    }

    static String decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != CONTENT_KIND) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        try {
            return decodeBody(frame.semantic());
        } catch (EOFException truncated) {
            throw new IOException("truncated v10 catalog reference", truncated);
        }
    }

    private static String decodeBody(byte[] semantic) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(semantic));
        int header = in.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            throw new IOException("unsupported v10 catalog reference header 0x"
                    + Integer.toHexString(header));
        }
        int subtype = WaypointCodec.readVarint(in);
        if (subtype != SUBTYPE_CATALOG_REFERENCE) {
            throw new IOException("unsupported v10 kind-6 subtype " + subtype);
        }
        int catalog = WaypointCodec.readVarint(in);
        if (catalog != CATALOG_WAYPOINTERMOD) {
            throw new IOException("reserved v10 catalog selector " + catalog);
        }
        int form = in.readUnsignedByte();
        String routeId;
        switch (form) {
            case ID_FORM_PACKED -> {
                byte[] packed = in.readNBytes(PACKED_ID_BYTES);
                if (packed.length != PACKED_ID_BYTES) {
                    throw new IOException("truncated v10 catalog route id");
                }
                routeId = Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
            }
            case ID_FORM_INLINE -> {
                int length = WaypointCodec.readVarint(in);
                if (length < 1 || length > MAX_INLINE_ID_CHARS) {
                    throw new IOException("v10 catalog route id length is outside limit");
                }
                byte[] ascii = in.readNBytes(length);
                if (ascii.length != length) throw new IOException("truncated v10 catalog route id");
                routeId = new String(ascii, StandardCharsets.US_ASCII);
                if (!isValidRouteId(routeId)) {
                    throw new IOException("v10 catalog route id has invalid characters");
                }
                if (packedId(routeId) != null) {
                    throw new IOException("non-canonical v10 catalog route id form");
                }
            }
            default -> throw new IOException("unsupported v10 catalog route id form " + form);
        }
        if (in.available() != 0) throw new IOException("trailing v10 catalog reference bytes");
        return routeId;
    }

    /** The 16 packed bytes of a canonical 22-character base64url id, or null when not packable. */
    static byte[] packedId(String routeId) {
        if (routeId == null || routeId.length() != PACKED_ID_CHARS
                || !isValidRouteId(routeId)) {
            return null;
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(routeId);
            if (packed.length != PACKED_ID_BYTES) return null;
            String roundTrip = Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
            return roundTrip.equals(routeId) ? Arrays.copyOf(packed, PACKED_ID_BYTES) : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
