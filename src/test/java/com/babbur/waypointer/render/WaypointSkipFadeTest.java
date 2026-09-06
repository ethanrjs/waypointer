package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointSkipFadeTest {
    @Test
    void resetAfterCompletionFadeHasEndedStillFadesTheFirstWaypointIn() {
        WaypointGroup group = route();
        group.setCurrentIndex(2);
        WaypointerConfig config = fadeConfig(0, 0);
        WaypointSkipFade.observe(group, config, 0);
        group.advancePast(2);
        WaypointSkipFade.observe(group, config, 10_000_000);
        WaypointSkipFade.observe(group, config, 210_000_000);
        group.resetProgress();
        WaypointSkipFade fade = WaypointSkipFade.observe(group, config, 220_000_000);
        assertTrue(fade.active());
        assertEquals(0, fade.alpha(0, 1, true));
        assertEquals(0, fade.alpha(2, .25f, false));
        WaypointSkipFade.observe(group, config, 320_000_000);
        assertEquals(.5f, fade.alpha(0, 1, true));
        assertEquals(0, fade.alpha(2, .25f, false));
        WaypointSkipFade.observe(group, config, 420_000_000);
        assertFalse(fade.active());
        assertEquals(1, fade.alpha(0, 1, true));
    }

    @Test
    void visibleContextKeepsItsOpacityAndInterruptedCurrentDoesNotBrighten() {
        for (int previous : new int[]{0, 1}) {
            WaypointGroup group = route();
            WaypointerConfig config = fadeConfig(previous, 2);
            config.setDimSequenceContextWaypoints(true);
            config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT);
            WaypointSkipFade.observe(group, config, 0);
            group.setCurrentIndex(1);
            WaypointSkipFade fade = WaypointSkipFade.observe(group, config, 10_000_000);
            assertEquals(.35f, fade.alpha(1, 1, true));
            assertEquals(0, fade.beamAlpha(1, 1, true));
            assertEquals(0, fade.tracerAlpha(1));
            assertEquals(1, fade.alpha(0, .25f, previous > 0));

            WaypointSkipFade.observe(group, config, 110_000_000);
            float before = fade.alpha(1, 1, true);
            assertEquals(.675f, before, .00001f);
            assertEquals(.135f, before * .2f, .00001f);
            group.setCurrentIndex(2);
            WaypointSkipFade.observe(group, config, 110_000_000);
            assertEquals(before, fade.alpha(1, .25f, previous > 0), .00001f);
            assertEquals(.35f, fade.alpha(2, 1, true));
            assertEquals(.5f, fade.tracerAlpha(1));
            assertEquals(.5f, fade.beamAlpha(1, .25f, false));
        }
    }

    @Test
    void allVisibleBeamsFollowContextAndBackwardSkipsKeepPreviousOpacity() {
        WaypointGroup group = route();
        WaypointerConfig config = fadeConfig(2, 2);
        config.setDimSequenceContextWaypoints(false);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        WaypointSkipFade.observe(group, config, 0);
        group.setCurrentIndex(1);
        WaypointSkipFade fade = WaypointSkipFade.observe(group, config, 10_000_000);
        assertEquals(.65f, fade.alpha(1, 1, true));
        assertEquals(.65f, fade.beamAlpha(1, 1, true));
        WaypointSkipFade.observe(group, config, 110_000_000);
        float previous = fade.alpha(0, .25f, true);
        float current = fade.alpha(1, 1, true);
        group.setCurrentIndex(0);
        WaypointSkipFade.observe(group, config, 110_000_000);
        assertEquals(previous, fade.alpha(0, 1, true));
        assertEquals(current, fade.alpha(1, .65f, true));
        WaypointSkipFade.observe(group, config, 210_000_000);
        assertEquals((previous + 1) / 2, fade.alpha(0, 1, true), .00001f);
    }

    @Test
    void enteringAnAlreadyVisibleSubwaypointDoesNotFadeItFromZero() {
        WaypointGroup group = WaypointGroup.create("Subwaypoints", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(Waypoint.at(1, 2, 3));
        group.add(Waypoint.at(4, 5, 6).withFlags(Waypoint.FLAG_SUBWAYPOINT));
        group.add(Waypoint.at(7, 8, 9));
        WaypointerConfig config = fadeConfig(0, 1);
        WaypointSkipFade.observe(group, config, 0);
        group.setCurrentTargetIndex(1);
        WaypointSkipFade fade = WaypointSkipFade.observe(group, config, 10_000_000);
        assertEquals(1, group.currentIndex());
        assertEquals(1, fade.alpha(1, 1, true));
        List<Integer> oldVisible = new ArrayList<>();
        group.forEachVisibleIndexAt(config.sequenceVisibility(), false, 0, -1, oldVisible::add);
        assertEquals(List.of(0, 1, 2), oldVisible);
        assertEquals(1, group.currentIndex());
    }

    @Test
    void resetDuringCompletionFadeKeepsTheOutgoingWaypoint() {
        WaypointGroup group = route();
        group.setCurrentIndex(2);
        observe(group, 200, 0);
        group.advancePast(2);
        observe(group, 200, 10_000_000);
        observe(group, 200, 110_000_000);

        group.resetProgress();
        WaypointSkipFade fade = observe(group, 200, 110_000_000);
        assertEquals(0, group.currentIndex());
        assertTrue(fade.active());
        assertEquals(2, fade.outgoing());
        assertEquals(.5f, fade.alpha(2, .25f, false));
        assertEquals(0, fade.alpha(0, 1, true));

        observe(group, 200, 210_000_000);
        assertEquals(.25f, fade.alpha(2, .25f, false));
        assertEquals(.5f, fade.alpha(0, 1, true));
        observe(group, 200, 310_000_000);
        assertFalse(fade.active());
        assertEquals(1, fade.alpha(0, 1, true));
    }

    @Test
    void finalStepFadesAwayEvenWithNoPreviousContext() {
        WaypointGroup group = route();
        group.setCurrentIndex(2);
        WaypointerConfig config = new WaypointerConfig();
        config.setSequencePreviousWaypointCount(0);
        config.setSequenceNextWaypointCount(0);
        WaypointRenderer renderer = new WaypointRenderer(new ActiveGroupManager(), config);
        observe(group, 200, 0);
        group.advancePast(2);
        observe(group, 200, 10_000_000);
        observe(group, 200, 110_000_000);
        assertTrue(group.isComplete());
        List<Integer> visible = new ArrayList<>();
        renderer.forEachFadingVisibleIndex(group, index -> {
            visible.add(index);
            assertEquals(.5f, renderer.alphaFor(group, index, WaypointRenderer.State.COMPLETED));
        });
        assertEquals(List.of(2), visible);
        observe(group, 200, 210_000_000);
        visible.clear();
        renderer.forEachFadingVisibleIndex(group, visible::add);
        assertTrue(visible.isEmpty());
    }

    @Test
    void manualAndAutomaticSkipsCrossfadeWithoutDelayingProgress() {
        WaypointGroup group = route();
        assertFalse(observe(group, 200, 0).active());
        group.setCurrentIndex(1);
        WaypointSkipFade fade = observe(group, 200, 10_000_000);
        assertEquals(1, group.currentIndex());
        assertEquals(1, fade.alpha(0, .25f, false));
        assertEquals(0, fade.alpha(1, 1, true));
        observe(group, 200, 110_000_000);
        assertEquals(.5f, fade.alpha(0, .25f, false));
        assertEquals(.5f, fade.alpha(1, 1, true));
        assertEquals(.5f, fade.tracerAlpha(0));
        assertEquals(.5f, fade.tracerAlpha(1));
        observe(group, 200, 210_000_000);
        assertFalse(fade.active());
        assertEquals(1, fade.alpha(1, 1, true));

        group.advancePast(1);
        observe(group, 200, 220_000_000);
        assertEquals(2, group.currentIndex());
        assertEquals(1, fade.outgoing());
    }

    @Test
    void outgoingIsKeptWithNoPreviousContextButStillRespectsHideFlags() {
        WaypointGroup group = route();
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointSkipFadeMs(200);
        config.setSequencePreviousWaypointCount(0);
        config.setSequenceNextWaypointCount(0);
        WaypointRenderer renderer = new WaypointRenderer(new ActiveGroupManager(), config);
        observe(group, 200, 0);
        group.advancePast(0);
        observe(group, 200, 10_000_000);
        observe(group, 200, 110_000_000);
        List<Integer> visible = new ArrayList<>();
        List<Float> alphas = new ArrayList<>();
        renderer.forEachFadingVisibleIndex(group, index -> {
            visible.add(index);
            alphas.add(renderer.alphaFor(group, index,
                    WaypointRenderer.stateFor(group, index, group.currentIndex())));
        });
        assertEquals(List.of(1, 0), visible);
        assertEquals(List.of(.5f, .5f), alphas);
        assertFalse(renderer.shouldHideCompletedSequenceWaypoint(group, 0, 1,
                WaypointRenderer.State.COMPLETED, false, group.get(0)));
        assertTrue(renderer.shouldHideCompletedSequenceWaypoint(group, 0, 1,
                WaypointRenderer.State.COMPLETED, false,
                group.get(0).withFlags(Waypoint.FLAG_HIDE_BEACON)));
        config.setShowCurrentSequenceWaypoint(false);
        visible.clear();
        renderer.forEachFadingVisibleIndex(group, visible::add);
        assertTrue(visible.isEmpty());
    }

    @Test
    void visiblePreviousContextFadesToItsNormalOpacityAndIsNotDuplicated() {
        WaypointGroup group = route();
        WaypointerConfig config = new WaypointerConfig();
        config.setSequencePreviousWaypointCount(1);
        config.setSequenceNextWaypointCount(0);
        WaypointRenderer renderer = new WaypointRenderer(new ActiveGroupManager(), config);
        observe(group, 200, 0);
        group.advancePast(0);
        observe(group, 200, 10_000_000);
        observe(group, 200, 110_000_000);
        List<Integer> visible = new ArrayList<>();
        renderer.forEachFadingVisibleIndex(group, index -> {
            visible.add(index);
            if (index == 0) assertEquals(.625f,
                    renderer.alphaFor(group, index, WaypointRenderer.State.COMPLETED));
        });
        assertEquals(List.of(0, 1), visible);
    }

    @Test
    void zeroDurationStaticRoutesAndEditsDoNotAnimate() {
        WaypointGroup group = route();
        assertNull(observe(group, 0, 0));
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        assertNull(observe(group, 200, 0));
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        observe(group, 200, 0);
        group.add(Waypoint.at(4, 5, 6));
        group.setCurrentIndex(1);
        assertFalse(observe(group, 200, 10_000_000).active());
    }

    @Test
    void rapidSkipsUseTheIncomingWaypointsCurrentOpacity() {
        WaypointGroup group = route();
        observe(group, 200, 0);
        group.setCurrentIndex(1);
        observe(group, 200, 10_000_000);
        observe(group, 200, 110_000_000);
        group.setCurrentIndex(2);
        WaypointSkipFade fade = observe(group, 200, 110_000_000);
        assertEquals(1, fade.outgoing());
        assertEquals(.5f, fade.alpha(1, .25f, false));
        assertEquals(0, fade.alpha(2, 1, true));
    }

    private static WaypointSkipFade observe(WaypointGroup group, int duration, long now) {
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointSkipFadeMs(duration);
        config.setSequencePreviousWaypointCount(0);
        config.setSequenceNextWaypointCount(0);
        return WaypointSkipFade.observe(group, config, now);
    }

    private static WaypointerConfig fadeConfig(int previous, int next) {
        WaypointerConfig config = new WaypointerConfig();
        config.setWaypointSkipFadeMs(200);
        config.setSequencePreviousWaypointCount(previous);
        config.setSequenceNextWaypointCount(next);
        return config;
    }

    private static WaypointGroup route() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(Waypoint.at(1, 2, 3));
        group.add(Waypoint.at(4, 5, 6));
        group.add(Waypoint.at(7, 8, 9));
        return group;
    }
}
