package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntFunction;

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

    private Map<Integer, Float> appearing = Map.of();
    private Map<Integer, Float> disappearing = Map.of();

    private WaypointSkipFade() {}

    public static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config) {
        return observe(group, config, System.nanoTime());
    }

    public static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config,
                                           IntFunction<WaypointerConfig> styleAt) {
        return observe(group, config, System.nanoTime(), styleAt);
    }

    static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config, long now) {
        return observe(group, config, now, index -> WaypointStyles.resolve(config, group,
                index >= 0 && index < group.size() ? group.get(index) : null));
    }

    private static WaypointSkipFade observe(WaypointGroup group, WaypointerConfig config, long now,
                                            IntFunction<WaypointerConfig> styleAt) {
        WaypointerConfig currentStyle = styleAt.apply(group.currentIndex());
        int durationMs = currentStyle.waypointSkipFadeMs();
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
                fade.appearing = Map.of();
                fade.disappearing = Map.of();
            } else if (next != fade.current) {
                int leaving = fade.current >= fade.size
                        ? fade.active() ? fade.outgoing : group.lastMainIndex()
                        : fade.current;
                float[] normal = fade.previousMarkerFactors(group, config, next, leaving, styleAt);
                float incomingMarker = fade.alpha(next, normal[0], normal[0] > 0);
                float outgoingMarker = fade.alpha(leaving, normal[1], normal[1] > 0);
                float incomingTracer = fade.tracerAlpha(next);
                float outgoingTracer = fade.tracerAlpha(leaving);
                int oldBeam = fade.current >= group.size() ? group.lastMainIndex()
                        : group.isSubwaypoint(fade.current)
                        ? group.parentMainIndex(fade.current) : fade.current;
                float incomingBeam = fade.previousBeamFactor(group, next, oldBeam, incomingMarker, styleAt);
                float outgoingBeam = fade.previousBeamFactor(group, leaving, oldBeam, outgoingMarker, styleAt);
                fade.captureSideTransitions(group, config, next, leaving, styleAt);
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
        fade.beamMode = currentStyle.beaconBeamMode();
        return fade;
    }

    private void advance(long now) {
        if (outgoing < 0) return;
        progress = Math.min(1, Math.max(0, (float) (now - started) / duration));
        if (progress >= 1) outgoing = -1;
    }

    private float[] previousMarkerFactors(WaypointGroup group, WaypointerConfig config,
                                          int incoming, int leaving, IntFunction<WaypointerConfig> styleAt) {
        float[] factors = new float[2];
        WaypointerConfig groupStyle = WaypointStyles.resolve(config, group, null);
        group.forEachVisibleIndexAt(groupStyle.sequenceVisibility(),
                groupStyle.keepSubwaypointsVisibleUntilNextWaypoint(), current, currentParent, index -> {
                    if (index != incoming && index != leaving) return;
                    if (WaypointWorldRenderer.shouldForceHideReachedWaypoint(index, current, group.get(index))) return;
                    float alpha = WaypointWorldRenderer.styleStateAlpha(
                            styleAt.apply(index), group,
                            WaypointWorldRenderer.stateFor(group, index, current, currentParent));
                    if (index == incoming) factors[0] = alpha;
                    if (index == leaving) factors[1] = alpha;
                });
        return factors;
    }

    private void captureSideTransitions(WaypointGroup group, WaypointerConfig config, int next,
                                        int leaving, IntFunction<WaypointerConfig> styleAt) {
        WaypointerConfig groupStyle = WaypointStyles.resolve(config, group, null);
        Set<Integer> before = new LinkedHashSet<>();
        group.forEachVisibleIndexAt(groupStyle.sequenceVisibility(),
                groupStyle.keepSubwaypointsVisibleUntilNextWaypoint(), current, currentParent, before::add);
        Set<Integer> after = new LinkedHashSet<>();
        group.forEachVisibleIndexAt(groupStyle.sequenceVisibility(),
                groupStyle.keepSubwaypointsVisibleUntilNextWaypoint(), next,
                group.activeSubwaypointParentIndex(), after::add);
        Map<Integer, Float> shown = new HashMap<>();
        for (int index : after) {
            if (index == next || index == leaving) continue;
            if (!before.contains(index) || active() && appearing.containsKey(index)) {
                float normal = before.contains(index) ? WaypointWorldRenderer.styleStateAlpha(
                        styleAt.apply(index), group,
                        WaypointWorldRenderer.stateFor(group, index, current, currentParent)) : 0;
                shown.put(index, alpha(index, normal, before.contains(index)));
            }
        }
        Set<Integer> hiddenCandidates = new LinkedHashSet<>(before);
        if (active()) {
            hiddenCandidates.addAll(disappearing.keySet());
            hiddenCandidates.add(outgoing);
        }
        Map<Integer, Float> hidden = new HashMap<>();
        for (int index : hiddenCandidates) {
            if (after.contains(index) || index == next || index == leaving
                    || index < 0 || index >= group.size()) continue;
            if (WaypointWorldRenderer.shouldForceHideReachedWaypoint(index, current, group.get(index))) continue;
            hidden.put(index, alpha(index, WaypointWorldRenderer.styleStateAlpha(styleAt.apply(index), group,
                    WaypointWorldRenderer.stateFor(group, index, current, currentParent)), before.contains(index)));
        }
        appearing = Map.copyOf(shown);
        disappearing = Map.copyOf(hidden);
    }

    public Set<Integer> fadingOut() {
        return active() ? disappearing.keySet() : Set.of();
    }

    private float previousBeamFactor(WaypointGroup group,
                                     int index, int oldBeam, float markerFactor, IntFunction<WaypointerConfig> styleAt) {
        if (beamMode == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE) return markerFactor;
        WaypointerConfig style = styleAt.apply(index);
        boolean visible = beamMode != WaypointerConfig.BeaconBeamMode.OFF && index == oldBeam
                && index >= 0 && index < group.size()
                && (current < group.size() || style.showCompleted())
                && !WaypointWorldRenderer.shouldForceHideReachedWaypoint(index, current, group.get(index));
        float normal = visible ? WaypointWorldRenderer.styleStateAlpha(
                style, group,
                WaypointWorldRenderer.stateFor(group, index, current, currentParent)) : 0;
        return beamAlpha(index, normal, visible);
    }

    public static WaypointSkipFade get(WaypointGroup group) { return STATES.get(group); }

    public boolean active() { return outgoing >= 0 && outgoing < size && progress < 1; }
    public int outgoing() { return active() ? outgoing : -1; }
    public boolean isOutgoing(int index) { return active() && index == outgoing; }

    public float alpha(int index, float normalAlpha, boolean normallyVisible) {
        float target = normallyVisible ? normalAlpha : 0;
        if (active() && index != current && index != outgoing) {
            Float incoming = appearing.get(index);
            if (incoming != null) return incoming + (target - incoming) * progress;
            Float leaving = disappearing.get(index);
            if (leaving != null) return leaving * (1 - progress);
        }
        return interpolate(index, target, incomingMarkerStart, outgoingMarkerStart);
    }

    public float beamAlpha(int index, float normalAlpha, boolean normallyVisible) {
        if (beamMode == WaypointerConfig.BeaconBeamMode.ALL_VISIBLE) {
            return alpha(index, normalAlpha, normallyVisible);
        }
        return interpolate(index, normallyVisible ? normalAlpha : 0,
                incomingBeamStart, outgoingBeamStart);
    }

    public float tracerAlpha(int index) {
        return interpolate(index, index == current ? 1 : 0, incomingTracerStart, outgoingTracerStart);
    }

    private float interpolate(int index, float target, float incomingStart, float outgoingStart) {
        if (!active()) return target;
        if (index == current) return incomingStart + (target - incomingStart) * progress;
        if (index == outgoing) return outgoingStart + (target - outgoingStart) * progress;
        return target;
    }
}
