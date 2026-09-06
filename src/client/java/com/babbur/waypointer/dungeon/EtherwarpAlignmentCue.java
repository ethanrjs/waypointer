package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.config.WaypointerConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EtherwarpAlignmentCue {
    // Prevent aim flicker from replaying the cue.
    private static final long JITTER_GUARD_MILLIS = 350L;

    private final ActiveGroupManager manager;
    private final Supplier<String> soundSelection;
    private final AlignmentState state = new AlignmentState(JITTER_GUARD_MILLIS);

    public EtherwarpAlignmentCue(ActiveGroupManager manager,
                                 Supplier<String> soundSelection) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.soundSelection = Objects.requireNonNull(soundSelection, "soundSelection");
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft minecraft) {
        String sound = soundSelection.get();
        if (sound == null || sound.isBlank()
                || minecraft == null
                || minecraft.level == null || minecraft.player == null) {
            state.update(null, false, System.currentTimeMillis());
            return;
        }
        LocalPlayer player = minecraft.player;
        Optional<EtherwarpAbility> ability = heldAbility(player);
        List<Target> targets = activeTargets();
        if (!canCheckAlignment(ability, player.isShiftKeyDown(), targets)) {
            state.update(null, false, System.currentTimeMillis());
            return;
        }

        Target aligned = EtherwarpTargetResolver.resolve(
                        minecraft.level, player, ability.get())
                .map(support -> matchedTarget(support, targets))
                .orElse(null);
        if (aligned == null) {
            state.update(null, false, System.currentTimeMillis());
            return;
        }
        if (!state.update(aligned.key(), true, System.currentTimeMillis())) return;
        CueSound cueSound = cueSound(sound);
        if (cueSound == null) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                cueSound.event(), cueSound.volume(), cueSound.pitch()));
    }

    private Optional<EtherwarpAbility> heldAbility(LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        Optional<EtherwarpAbility> main = DungeonItemIdentity.etherwarpAbility(mainHand);
        return main.isPresent() ? main
                : DungeonItemIdentity.etherwarpAbility(player.getOffhandItem());
    }

    List<Target> activeTargets() {
        List<Target> targets = new ArrayList<>();
        for (WaypointGroup group : manager.activeGroups()) {
            for (int index = 0; index < group.size(); index++) {
                Waypoint waypoint = group.get(index);
                if (waypoint.isDisabled()) continue;
                targets.add(new Target(group.id() + ":" + index, waypoint));
            }
        }
        return List.copyOf(targets);
    }

    static boolean canCheckAlignment(
            Optional<EtherwarpAbility> ability, boolean sneaking, List<Target> targets) {
        return ability != null && ability.isPresent()
                && ability.get().canUse(sneaking)
                && targets != null && !targets.isEmpty();
    }

    static Target matchedTarget(BlockPos supportBlock, Iterable<Target> targets) {
        if (supportBlock == null || targets == null) return null;
        for (Target target : targets) {
            if (target != null && EtherwarpTargetResolver.alignsWith(
                    supportBlock, target.waypoint())) {
                return target;
            }
        }
        return null;
    }

    static CueSound cueSound(String sound) {
        if (!WaypointerConfig.isValidSoundId(sound) || sound.isBlank()) return null;
        Identifier id = Identifier.parse(sound.trim());
        return switch (id.toString()) {
            case "minecraft:entity.experience_orb.pickup" -> new CueSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.65F, 0.30F);
            case "minecraft:block.note_block.pling" -> new CueSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.85F, 1.80F);
            case "minecraft:block.bell.use" -> new CueSound(SoundEvents.BELL_BLOCK, 0.70F, 1.30F);
            default -> new CueSound(SoundEvent.createVariableRangeEvent(id), 1.0F, 1.0F);
        };
    }

    record Target(String key, Waypoint waypoint) {
    }

    record CueSound(SoundEvent event, float volume, float pitch) {
    }

    static final class AlignmentState {
        private final long jitterGuardMillis;
        private String targetKey;
        private boolean aligned;
        private long lastCueAtMillis = Long.MIN_VALUE;

        AlignmentState(long jitterGuardMillis) {
            this.jitterGuardMillis = jitterGuardMillis;
        }

        boolean update(String nextTargetKey, boolean nextAligned, long nowMillis) {
            if (nextTargetKey == null) {
                targetKey = null;
                aligned = false;
                return false;
            }
            boolean targetChanged = !nextTargetKey.equals(targetKey);
            boolean transition = nextAligned && (targetChanged || !aligned);
            targetKey = nextTargetKey;
            if (!transition) {
                aligned = nextAligned;
                return false;
            }
            if (elapsed(nowMillis, lastCueAtMillis) < jitterGuardMillis) {
                aligned = false;
                return false;
            }
            aligned = true;
            lastCueAtMillis = nowMillis;
            return true;
        }

        private static long elapsed(long nowMillis, long previousMillis) {
            if (previousMillis == Long.MIN_VALUE) return Long.MAX_VALUE;
            return Math.max(0L, nowMillis - previousMillis);
        }
    }
}
