package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.config.WaypointerConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtherwarpAlignmentCueTest {
    @Test
    void cuePlaysOnlyOnAlignmentTransitions() {
        EtherwarpAlignmentCue.AlignmentState state =
                new EtherwarpAlignmentCue.AlignmentState(350);

        assertFalse(state.update("route:0", false, 1_000));
        assertTrue(state.update("route:0", true, 1_010));
        assertFalse(state.update("route:0", true, 2_000));
        assertFalse(state.update("route:0", false, 2_010));
        assertTrue(state.update("route:0", true, 2_500));
    }

    @Test
    void jitterGuardAndTargetChangesDoNotSpam() {
        EtherwarpAlignmentCue.AlignmentState state =
                new EtherwarpAlignmentCue.AlignmentState(350);
        assertTrue(state.update("route:0", true, 1_000));
        assertFalse(state.update("route:0", false, 1_050));
        assertFalse(state.update("route:0", true, 1_100));
        assertFalse(state.update("route:1", true, 1_200));
        assertTrue(state.update("route:2", true, 1_500));
        assertFalse(state.update(null, false, 1_600));
    }

    @Test
    void suppressedNewTargetCuesWhenItRemainsAlignedPastTheGuard() {
        EtherwarpAlignmentCue.AlignmentState state =
                new EtherwarpAlignmentCue.AlignmentState(350);

        assertTrue(state.update("route:0", true, 1_000));
        assertFalse(state.update("route:1", true, 1_200));
        assertFalse(state.update("route:1", true, 1_349));
        assertTrue(state.update("route:1", true, 1_350));
        assertFalse(state.update("route:1", true, 2_000));
    }

    @Test
    void regularUnflaggedCurrentWaypointIsEligible() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        Waypoint waypoint = Waypoint.at(2, 70, 2);
        WaypointGroup route = new WaypointGroup("regular", "Regular", "hub");
        route.add(waypoint);
        manager.add(route);

        EtherwarpAlignmentCue cue = new EtherwarpAlignmentCue(manager,
                () -> WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE.id());

        assertEquals(List.of(new EtherwarpAlignmentCue.Target("regular:0", route.get(0))),
                cue.activeTargets());
    }

    @Test
    void everyEnabledWaypointInAnActiveRouteIsEligible() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        Waypoint first = Waypoint.at(2, 70, 2);
        Waypoint second = Waypoint.at(8, 72, -3);
        Waypoint disabled = Waypoint.at(12, 75, 4)
                .withFlags(Waypoint.FLAG_DISABLED);
        WaypointGroup route = new WaypointGroup("regular", "Regular", "hub");
        route.addAll(List.of(first, second, disabled));
        manager.add(route);

        EtherwarpAlignmentCue cue = new EtherwarpAlignmentCue(manager,
                () -> WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE.id());

        assertEquals(List.of(
                        new EtherwarpAlignmentCue.Target("regular:0", route.get(0)),
                        new EtherwarpAlignmentCue.Target("regular:1", route.get(1))),
                cue.activeTargets());
        assertEquals("regular:1", EtherwarpAlignmentCue.matchedTarget(
                new BlockPos(second.x(), second.y() - 1, second.z()),
                cue.activeTargets()).key());
    }

    @Test
    void aimedSecondActiveRouteIsNotBlockedByTheFirstRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(Zone.fromId("hub"));
        Waypoint first = Waypoint.at(2, 70, 2);
        Waypoint second = Waypoint.at(20, 75, -8);
        WaypointGroup firstRoute = new WaypointGroup("first", "First", "hub");
        firstRoute.add(first);
        WaypointGroup secondRoute = new WaypointGroup("second", "Second", "hub");
        secondRoute.add(second);
        manager.addAll(List.of(firstRoute, secondRoute));
        EtherwarpAlignmentCue cue = new EtherwarpAlignmentCue(manager,
                () -> WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE.id());

        List<EtherwarpAlignmentCue.Target> targets = cue.activeTargets();
        EtherwarpAlignmentCue.Target matched = EtherwarpAlignmentCue.matchedTarget(
                new BlockPos(second.x(), second.y() - 1, second.z()), targets);

        assertEquals(2, targets.size());
        assertNotNull(matched);
        assertEquals("second:0", matched.key());
    }

    @Test
    void alignmentCheckRequiresSneakingAndAnEtherwarpItem() {
        EtherwarpAlignmentCue.Target target = new EtherwarpAlignmentCue.Target(
                "route:0", Waypoint.at(1, 2, 3));
        Optional<EtherwarpAbility> ability = Optional.of(
                new EtherwarpAbility(EtherwarpAbility.BASE_RANGE));

        assertTrue(EtherwarpAlignmentCue.canCheckAlignment(
                ability, true, List.of(target)));
        assertFalse(EtherwarpAlignmentCue.canCheckAlignment(
                ability, false, List.of(target)));
        assertFalse(EtherwarpAlignmentCue.canCheckAlignment(
                Optional.empty(), true, List.of(target)));
    }

    @Test
    void soundIdsPreserveExistingCuesAndAcceptCustomSounds() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertSame(SoundEvents.EXPERIENCE_ORB_PICKUP, EtherwarpAlignmentCue.cueSound(
                WaypointerConfig.EtherwarpAlignmentSound.EXPERIENCE.id()).event());
        assertSame(SoundEvents.NOTE_BLOCK_PLING.value(), EtherwarpAlignmentCue.cueSound(
                WaypointerConfig.EtherwarpAlignmentSound.PLING.id()).event());
        assertSame(SoundEvents.BELL_BLOCK, EtherwarpAlignmentCue.cueSound(
                WaypointerConfig.EtherwarpAlignmentSound.BELL.id()).event());
        assertNull(EtherwarpAlignmentCue.cueSound(
                WaypointerConfig.EtherwarpAlignmentSound.OFF.id()));
        assertEquals("custom:cue/ready",
                EtherwarpAlignmentCue.cueSound("custom:cue/ready").event().location().toString());
        assertSame(SoundEvents.NOTE_BLOCK_PLING.value(),
                EtherwarpAlignmentCue.cueSound("minecraft:block.note_block.pling").event());
        assertNull(EtherwarpAlignmentCue.cueSound("not a sound"));
    }
}
