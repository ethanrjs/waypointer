package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.WeakHashMap;

/** Transient rendering state; route progress is never delayed. */
public final class WaypointSkipFade {
    private static final WeakHashMap<WaypointGroup, WaypointSkipFade> STATES = new WeakHashMap<>();
    private int current;
    private int currentParent;
    private int outgoing = -1;
    private int size;
    private Waypoint currentWaypoint;
    private long started;
    private long lastSeen;
    private long duration;
    private float incomingMarkerStart;
    private float outgoingMarkerStart = 1;
    private float incomingBeamStart;
    private float outgoingBeamStart = 1;
    private float incomingTracerStart;
    private float outgoingTracerStart = 1;
    private float progress = 1;
    private WaypointerConfig.BeaconBeamMode beamMode;
    boolean outgoingNormallyVisible;

    private WaypointSkipFade() {}

    public static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config) {
        return observe(group, config, System.nanoTime());
    }

    static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config, long now) {
        int durationMs = config.waypointSkipFadeMs();
        if (durationMs <= 0 || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE
                || group.focusedVisibleIndex() >= 0) {
            STATES.remove(group);
            return null;
        }
        WaypointSkipFade fade = STATES.get(group);
        int next = group.currentIndex();
        Waypoint waypoint = group.current();
        if (fade == null) {
            fade = new WaypointSkipFade();
            STATES.put(group, fade);
            fade.current = next;
        } else {
            fade.advance(now);
            if (fade.size != group.size() || now - fade.lastSeen > 1_000_000_000L
                    || fade.current < group.size() && fade.current >= 0
                    && group.get(fade.current) != fade.currentWaypoint) {
                fade.outgoing = -1;
                fade.progress = 1;
            } else if (next != fade.current) {
                int leaving = fade.current >= fade.size
                        ? fade.active() ? fade.outgoing : group.lastMainIndex()
                        : fade.current;
                float[] normal = fade.previousMarkerFactors(group, config, next, leaving);
                float incomingMarker = fade.alpha(next, normal[0], normal[0] > 0);
                float outgoingMarker = fade.alpha(leaving, normal[1], normal[1] > 0);
                float incomingTracer = fade.tracerAlpha(next);
                float outgoingTracer = fade.tracerAlpha(leaving);
                int oldBeam = fade.current >= group.size() ? group.lastMainIndex()
                        : group.isSubwaypoint(fade.current)
                        ? group.parentMainIndex(fade.current) : fade.current;
                float incomingBeam = fade.previousBeamFactor(group, config, next, oldBeam, incomingMarker);
                float outgoingBeam = fade.previousBeamFactor(group, config, leaving, oldBeam, outgoingMarker);
                fade.incomingMarkerStart = incomingMarker;
                fade.outgoingMarkerStart = outgoingMarker;
                fade.incomingBeamStart = incomingBeam;
                fade.outgoingBeamStart = outgoingBeam;
                fade.incomingTracerStart = incomingTracer;
                fade.outgoingTracerStart = outgoingTracer;
                fade.outgoing = leaving;
                fade.started = now;
                fade.duration = durationMs * 1_000_000L;
                fade.progress = 0;
            }
        }
        fade.current = next;
        fade.currentParent = group.activeSubwaypointParentIndex();
        fade.currentWaypoint = waypoint;
        fade.size = group.size();
        fade.lastSeen = now;
        fade.beamMode = config.beaconBeamMode();
        return fade;
    }

    private void advance(long now) {
        if (outgoing < 0) return;
        progress = Math.min(1, Math.max(0, (float) (now - started) / duration));
        if (progress >= 1) outgoing = -1;
    }

    private float[] previousMarkerFactors(WaypointGroup group, WaypointerConfig config,
                                          int incoming, int leaving) {
        float[] factors = new float[2];
        group.forEachVisibleIndexAt(config.sequenceVisibility(),
                config.keepSubwaypointsVisibleUntilNextWaypoint(), current, currentParent, index -> {
                    if (index != incoming && index != leaving) return;
                    if (WaypointWorldRenderer.shouldForceHideReachedWaypoint(index, current, group.get(index))) return;
                    float alpha = WaypointWorldRenderer.roleAlpha(group,
                            WaypointWorldRenderer.stateFor(group, index, current, currentParent),
                            config.dimSequenceContextWaypoints());
                    if (index == incoming) factors[0] = alpha;
                    if (index == leaving) factors[1] = alpha;
                });
        return factors;
    }

    private float previousBeamFactor(WaypointGroup group, WaypointerConfig config,
                                     int index, int oldBeam, float markerFactor) {
        if (beamMode == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE) return markerFactor;
        boolean visible = beamMode != WaypointerConfig.BeaconBeamMode.OFF && index == oldBeam
                && index >= 0 && index < group.size()
                && (current < group.size() || config.showCompleted())
                && !WaypointWorldRenderer.shouldForceHideReachedWaypoint(index, current, group.get(index));
        float normal = visible ? WaypointWorldRenderer.roleAlpha(group,
                WaypointWorldRenderer.stateFor(group, index, current, currentParent),
                config.dimSequenceContextWaypoints()) : 0;
        return beamAlpha(index, normal, visible);
    }

    static WaypointSkipFade get(WaypointGroup group) { return STATES.get(group); }

    public boolean active() { return outgoing >= 0 && outgoing < size && progress < 1; }
    int outgoing() { return active() ? outgoing : -1; }
    boolean isOutgoing(int index) { return active() && index == outgoing; }

    float alpha(int index, float normalAlpha, boolean normallyVisible) {
        return interpolate(index, normallyVisible ? normalAlpha : 0,
                incomingMarkerStart, outgoingMarkerStart);
    }

    float beamAlpha(int index, float normalAlpha, boolean normallyVisible) {
        if (beamMode == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE) {
            return alpha(index, normalAlpha, normallyVisible);
        }
        return interpolate(index, normallyVisible ? normalAlpha : 0,
                incomingBeamStart, outgoingBeamStart);
    }

    float tracerAlpha(int index) {
        return interpolate(index, index == current ? 1 : 0, incomingTracerStart, outgoingTracerStart);
    }

    private float interpolate(int index, float target, float incomingStart, float outgoingStart) {
        if (!active()) return target;
        if (index == current) return incomingStart + (target - incomingStart) * progress;
        if (index == outgoing) return outgoingStart + (target - outgoingStart) * progress;
        return target;
    }
}
