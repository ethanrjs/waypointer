package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.WaypointGroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates catalog data before it reaches local route state. */
public final class CatalogProtocol {
    /**
     * The public catalog remains pinned to its deployed canonical V9 contract.
     * Product clipboard/chat exports may advance independently.
     */
    private static final int CATALOG_CODEC_VERSION = 9;

    private CatalogProtocol() {
    }

    public static int currentCodecVersion() {
        return CATALOG_CODEC_VERSION;
    }

    public static void requireInstallable(CatalogRouteSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.codecVersion() != currentCodecVersion()) {
            throw new IllegalArgumentException(
                    "Catalog route needs an unsupported codec version");
        }
    }

    public static CatalogRouteDetails validateDetails(
            CatalogRouteSummary requested, CatalogRouteDetails details) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(details, "details");
        CatalogRouteSummary actual = Objects.requireNonNull(details.summary(), "details.summary");
        if (!SelectedRouteMetadata.from(requested).equals(
                SelectedRouteMetadata.from(actual))) {
            throw new IllegalArgumentException(
                    "Catalog route metadata changed while it was selected");
        }
        return details;
    }

    static PayloadManifest inspectPayload(String payload) {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeCanonicalV9(payload);
        WaypointImporter.validateCatalogEmbeddedZones(decoded.groups());
        List<WaypointGroup> groups = List.copyOf(decoded.groups());
        PublishManifest publishManifest = publishManifest(groups, payload);
        return new PayloadManifest(
                groups, decoded.label(), publishManifest.groupCount(),
                publishManifest.waypointCount(), publishManifest.zoneId(),
                publishManifest.codecVersion(), publishManifest.payloadSha256());
    }

    /**
     * Encodes immutable route snapshots with the existing catalog V9 feature set
     * and records the resulting manifest in the same operation. The returned
     * capability can only be constructed here, so the publish client may skip
     * decoding bytes it just encoded itself.
     */
    public static PreparedCatalogPayload prepareCatalogPayload(
            List<WaypointGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        List<WaypointGroup> snapshots = new ArrayList<>(groups.size());
        for (WaypointGroup group : groups) {
            snapshots.add(Objects.requireNonNull(group, "group").exportSnapshot());
        }
        List<WaypointGroup> immutableSnapshots = List.copyOf(snapshots);
        WaypointImporter.validateCatalogEmbeddedZones(immutableSnapshots);
        String payload = WaypointCodec.encodeCatalog(immutableSnapshots);
        return new PreparedCatalogPayload(
                payload, publishManifest(immutableSnapshots, payload));
    }

    static PublishExpectation validatePublishRequest(
            CatalogPublishRequest request, PublisherIdentity identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        return publishExpectation(
                request, identity, inspectPayload(request.payload()).publishManifest());
    }

    static PublishExpectation validatePreparedPublishRequest(
            CatalogPublishRequest request,
            PublisherIdentity identity,
            PreparedCatalogPayload prepared) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(prepared, "prepared");
        if (!prepared.payload.equals(request.payload())) {
            throw new IllegalArgumentException(
                    "Prepared catalog payload does not match the publish request");
        }
        return publishExpectation(request, identity, prepared.manifest);
    }

    private static PublishExpectation publishExpectation(
            CatalogPublishRequest request,
            PublisherIdentity identity,
            PublishManifest manifest) {
        if (request.zoneId() != null && !request.zoneId().isBlank()
                && !manifest.zoneId().equals(request.zoneId())) {
            throw new IllegalArgumentException(
                    "Publish request zone does not match its route payload");
        }
        String expectedName = expectedPublisherName(request, identity);
        return new PublishExpectation(
                manifest, expectedName, request.title(), request.description().strip(),
                request.visibility(), manifest.zoneId());
    }

    static CatalogPublishReceipt validatePublishResponse(
            CatalogPublishResult result,
            CatalogPublishRequest request,
            PublisherIdentity identity,
            PublishExpectation expected) {
        if (result == null || result.route() == null) {
            throw invalidPublishResponse("The catalog returned no published route.");
        }
        CatalogRouteSummary route = result.route();
        if (!identity.publisherId().equals(route.publisherId())
                || !expected.publisherName().equals(route.authorName())) {
            throw invalidPublishResponse(
                    "The catalog returned a route for a different publisher.");
        }
        if (!expected.title().equals(route.title())) {
            throw invalidPublishResponse("The catalog returned a different route title.");
        }
        if (!expected.description().equals(route.description())) {
            throw invalidPublishResponse("The catalog returned a different route description.");
        }
        if (!expected.visibility().wireName().equals(route.visibility())) {
            throw invalidPublishResponse("The catalog returned a different route visibility.");
        }
        PublishManifest manifest = expected.manifest();
        if (!expected.zoneId().equals(route.zoneId())
                || manifest.groupCount() != route.groupCount()
                || manifest.waypointCount() != route.waypointCount()
                || manifest.codecVersion() != route.codecVersion()) {
            throw invalidPublishResponse(
                    "The catalog returned metadata for a different route payload.");
        }
        if (route.version() <= 0) {
            throw invalidPublishResponse("The catalog returned an invalid route version.");
        }
        String expectedSharePath = "/r/" + route.id();
        if (!expectedSharePath.equals(route.sharePath())) {
            throw invalidPublishResponse("The catalog returned an invalid route share path.");
        }
        requireInstant(route.createdAt(), "creation time");
        requireInstant(route.updatedAt(), "update time");
        return new CatalogPublishReceipt(
                result, request, identity, expected.publisherName(),
                manifest.payloadSha256(), manifest.groupCount(), manifest.waypointCount(),
                manifest.zoneId(), manifest.codecVersion());
    }

    static void validateSummaryAgainstPayload(
            CatalogRouteSummary summary, PayloadManifest manifest) {
        requireInstallable(summary);
        if (summary.groupCount() != manifest.groupCount()
                || summary.waypointCount() != manifest.waypointCount()
                || summary.codecVersion() != manifest.codecVersion()
                || !summary.zoneId().equals(manifest.zoneId())) {
            throw new IllegalArgumentException(
                    "Catalog route metadata does not match its route code");
        }
    }

    static String normalizeApiRoot(String apiRoot) {
        return CatalogRouteProvenance.normalizeApiRoot(apiRoot);
    }

    static String payloadHash(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static PublishManifest publishManifest(
            List<WaypointGroup> groups, String payload) {
        int waypointCount = groups.stream().mapToInt(WaypointGroup::size).sum();
        Set<String> zones = new LinkedHashSet<>();
        for (WaypointGroup group : groups) zones.add(group.zoneId());
        String zoneId = zones.size() == 1 ? zones.iterator().next() : "multiple";
        return new PublishManifest(
                groups.size(), waypointCount, zoneId,
                currentCodecVersion(), payloadHash(payload));
    }

    private static String expectedPublisherName(
            CatalogPublishRequest request, PublisherIdentity identity) {
        if (identity.publisherName() == null) {
            return PublisherNamePolicy.requireValid(request.publisherName());
        }
        if (request.publisherName() != null
                && !identity.publisherName().equals(request.publisherName())) {
            throw new IllegalArgumentException(
                    "The publish request conflicts with the permanent publisher name");
        }
        return identity.publisherName();
    }

    private static void requireInstant(String value, String name) {
        if (value == null || value.isEmpty()) return;
        try {
            Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalidPublishResponse("The catalog returned an invalid " + name + ".");
        }
    }

    private static CatalogApiException invalidPublishResponse(String message) {
        return new CatalogApiException(201, "publish_response_mismatch", message);
    }

    record PayloadManifest(
            List<WaypointGroup> groups,
            String label,
            int groupCount,
            int waypointCount,
            String zoneId,
            int codecVersion,
            String payloadSha256) {
        PublishManifest publishManifest() {
            return new PublishManifest(
                    groupCount, waypointCount, zoneId, codecVersion, payloadSha256);
        }
    }

    record PublishManifest(
            int groupCount,
            int waypointCount,
            String zoneId,
            int codecVersion,
            String payloadSha256) {
    }

    record PublishExpectation(
            PublishManifest manifest,
            String publisherName,
            String title,
            String description,
            CatalogPublishRequest.Visibility visibility,
            String zoneId) {
    }

    public static final class PreparedCatalogPayload {
        private final String payload;
        private final PublishManifest manifest;

        private PreparedCatalogPayload(String payload, PublishManifest manifest) {
            this.payload = payload;
            this.manifest = manifest;
        }

        public String payload() {
            return payload;
        }
    }

    private record SelectedRouteMetadata(
            String id,
            String title,
            String description,
            String authorName,
            String publisherId,
            String visibility,
            String zoneId,
            int waypointCount,
            int groupCount,
            int codecVersion,
            int version,
            String sharePath) {

        private static SelectedRouteMetadata from(CatalogRouteSummary route) {
            return new SelectedRouteMetadata(
                    route.id(), route.title(), route.description(), route.authorName(),
                    route.publisherId(), route.visibility(), route.zoneId(),
                    route.waypointCount(), route.groupCount(), route.codecVersion(),
                    route.version(), route.sharePath());
        }
    }
}
