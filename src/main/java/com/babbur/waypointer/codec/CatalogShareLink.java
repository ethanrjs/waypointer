package com.babbur.waypointer.codec;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalog links resolve like kind-6 subtype-2 {@code WP:} references. */
public final class CatalogShareLink {

    public static final String HOST = "waypointermod.com";
    public static final String SHARE_PREFIX = "https://" + HOST + "/r/";

    private static final Pattern LINK = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?" + Pattern.quote(HOST)
                    + "/r/([A-Za-z0-9_-]{1,64})(?=$|[/?#\\s]|[.,;:!?)\\]}'\"])");

    private CatalogShareLink() {}

    public static String forRouteId(String routeId) {
        if (!V10CatalogReferenceCodec.isValidRouteId(routeId)) {
            throw new IllegalArgumentException("invalid catalog route id");
        }
        return SHARE_PREFIX + routeId;
    }

    public static Optional<String> routeIdFromLink(String text) {
        if (text == null) return Optional.empty();
        String trimmed = text.trim();
        Matcher matcher = LINK.matcher(trimmed);
        if (!matcher.lookingAt()) return Optional.empty();
        String rest = trimmed.substring(matcher.end());
        // Allow only a bare trailing slash or a query/fragment after the id.
        if (!rest.isEmpty() && !rest.equals("/") && rest.charAt(0) != '?' && rest.charAt(0) != '#') {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1));
    }

    public static Matcher find(String text) {
        return LINK.matcher(text);
    }
}
