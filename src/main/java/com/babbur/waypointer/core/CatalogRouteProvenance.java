package com.babbur.waypointer.core;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/** Local-only provenance for one group installed from the public route catalog. */
public record CatalogRouteProvenance(
        String apiRoot,
        String routeId,
        int routeVersion,
        int codecVersion,
        String payloadSha256,
        int groupIndex,
        int groupCount) {

    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern PAYLOAD_HASH = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_GROUPS = 64;

    public CatalogRouteProvenance {
        apiRoot = normalizeApiRoot(apiRoot);
        routeId = requireMatch(routeId, "route ID", ROUTE_ID);
        payloadSha256 = requireMatch(payloadSha256, "payload hash", PAYLOAD_HASH);
        if (routeVersion <= 0) throw new IllegalArgumentException("Invalid route version");
        if (codecVersion <= 0) throw new IllegalArgumentException("Invalid codec version");
        if (groupCount <= 0 || groupCount > MAX_GROUPS) {
            throw new IllegalArgumentException("Invalid catalog group count");
        }
        if (groupIndex < 0 || groupIndex >= groupCount) {
            throw new IllegalArgumentException("Invalid catalog group index");
        }
    }

    public boolean belongsTo(String expectedApiRoot, String expectedRouteId) {
        return apiRoot.equals(normalizeApiRoot(expectedApiRoot))
                && routeId.equals(expectedRouteId);
    }

    public static String normalizeApiRoot(String value) {
        Objects.requireNonNull(value, "apiRoot");
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid catalog API root");
            }
            String text = uri.toString();
            return text.endsWith("/") ? text : text + "/";
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument
                    && "Invalid catalog API root".equals(argument.getMessage())) {
                throw argument;
            }
            throw new IllegalArgumentException("Invalid catalog API root", failure);
        }
    }

    private static String requireMatch(String value, String name, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
}
