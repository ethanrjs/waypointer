package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;

import java.io.IOException;
import java.util.List;

/** Typed V10 route selector/dispatcher for route content kinds. */
final class V10RouteCodec {

    private V10RouteCodec() {}

    static boolean canEncodeBareSelection(
            List<WaypointGroup> groups, WaypointCodec.Options options) {
        if (groups == null || options == null || groups.isEmpty()
                || !options.hasAllExportFieldsOff()) {
            return false;
        }
        if (groups.size() == 1 && V10BareRouteCodec.canEncode(
                groups.getFirst(), WaypointCodec.Options.BARE_COORDINATES)) {
            return true;
        }
        return V10BareRoutePackCodec.canEncode(
                groups, WaypointCodec.Options.BARE_COORDINATES);
    }

    static String encode(List<WaypointGroup> groups, WaypointCodec.Options options)
            throws IOException {
        // Exact all-off regular routes use the coordinate-only wire kinds even
        // when the caller built those six visible fields manually. The hidden
        // BARE_COORDINATES marker controls fail-closed behavior: an ordinary
        // all-off request may still fall back to kind 0 when its route type or
        // bounds are ineligible, but an explicit destructive projection may not.
        boolean exactAllOff = options.hasAllExportFieldsOff();
        if (canEncodeBareSelection(groups, options)) {
            if (groups.size() == 1
                    && V10BareRouteCodec.canEncode(
                            groups.getFirst(), WaypointCodec.Options.BARE_COORDINATES)) {
                return V10BareRouteCodec.encodeCandidate(groups.getFirst()).transport();
            }
            if (V10BareRoutePackCodec.canEncode(
                    groups, WaypointCodec.Options.BARE_COORDINATES)) {
                return V10BareRoutePackCodec.encode(groups);
            }
        } else if (exactAllOff && !groups.isEmpty()
                && options.isBareCoordinateProjection()) {
            throw new IllegalArgumentException(
                    "coordinate-only export requires bounded regular routes representable by V10");
        }

        V10Transport.Outbound best = V10GeneralRouteCodec.encodeCandidate(groups, options);
        if (groups.size() == 1 && V10CompactRouteCodec.canEncode(groups.get(0), options)) {
            V10Transport.Outbound compact = V10CompactRouteCodec.encodeCandidate(
                    groups.get(0), options);
            if (compact.compareTo(best) < 0) best = compact;
        }
        if (groups.size() == 1 && V10SparseRouteCodec.canEncode(groups.get(0), options)) {
            V10Transport.Outbound sparse = V10SparseRouteCodec.encodeCandidate(
                    groups.get(0), options);
            if (sparse.compareTo(best) < 0) best = sparse;
        }
        return best.transport();
    }

    static WaypointCodec.Decoded decode(V10Transport.CheckedFrame frame) throws IOException {
        return switch (frame.contentKind()) {
            case V10GeneralRouteCodec.CONTENT_KIND -> V10GeneralRouteCodec.decode(frame);
            case V10CompactRouteCodec.CONTENT_KIND ->
                    new WaypointCodec.Decoded(List.of(V10CompactRouteCodec.decode(frame)), "");
            case 2 -> new WaypointCodec.Decoded(List.of(V10BareRouteCodec.decode(frame)), "");
            case V10SparseRouteCodec.CONTENT_KIND ->
                    new WaypointCodec.Decoded(List.of(V10SparseRouteCodec.decode(frame)), "");
            case V10BareRoutePackCodec.CONTENT_KIND -> decodeKind6(frame);
            default -> throw new IOException("unsupported v10 content kind "
                    + frame.contentKind());
        };
    }

    /** Kind 6 subtypes: 0 is the bare route pack, 1 is the route library. */
    private static WaypointCodec.Decoded decodeKind6(V10Transport.CheckedFrame frame)
            throws IOException {
        if (!V10RouteLibraryCodec.isLibrarySemantic(frame.semantic())) {
            return new WaypointCodec.Decoded(V10BareRoutePackCodec.decode(frame), "");
        }
        RouteLibraryCodec.Decoded library = V10RouteLibraryCodec.decode(frame);
        // The route-only entry point still gets faithful groups: manual color
        // snapshots and paints are applied here, folders ride along as metadata
        // for importers that own a route manager.
        try {
            library.metadata().applyTo(library.groups());
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid v10 route library metadata: "
                    + failure.getMessage(), failure);
        }
        return new WaypointCodec.Decoded(library.groups(), library.label(), library.metadata());
    }
}
