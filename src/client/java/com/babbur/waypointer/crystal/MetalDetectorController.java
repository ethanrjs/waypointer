package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public final class MetalDetectorController {
    public static final String GROUP_ID = "crystal_hollows:metal_detector";
    private static MetalDetectorController installed;
    private final ActiveGroupManager manager;
    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;
    private final MetalDetectorSolver solver = new MetalDetectorSolver();
    private final MetalDetectorSolver.PositionStability positionStability = new MetalDetectorSolver.PositionStability();
    private List<CrystalHollowsPosition> shown = List.of();
    private String serverId;
    private Object level;
    private boolean shownSolved;
    private CrystalHollowsPosition announced;
    private long foundAtNanos;

    public MetalDetectorController(ActiveGroupManager manager, CrystalHollowsTracker tracker,
                                   WaypointerConfig config) {
        this.manager = manager;
        this.tracker = tracker;
        this.config = config;
    }

    public static boolean isDetectorGroup(WaypointGroup group) {
        return group != null && group.runtimeOnly() && GROUP_ID.equals(group.id());
    }

    public void install() {
        installed = this;
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled() || client.player == null || client.level != level
                    || !Objects.equals(serverId, tracker.serverId())) clear();
            level = client.level;
            serverId = tracker.serverId();
            if (enabled() && client.player != null) {
                positionStability.tick(client.player.getX(), detectorY(client), client.player.getZ(), System.nanoTime());
            }
        });
    }

    /** Dedicated action-bar packets bypass Fabric's game-message event. */
    public static Component onActionBarPacket(Component message) {
        return installed == null ? message : installed.onMessage(message, true);
    }

    private boolean enabled() {
        return config.crystalHollowsMetalDetector() && tracker != null && tracker.active()
                && tracker.sidebarStructure() == CrystalHollowsStructure.MINES_OF_DIVAN;
    }

    private Component onMessage(Component message, boolean overlay) {
        if (!enabled()) { clear(); return message; }
        Minecraft client = Minecraft.getInstance();
        if (client.level != level || !Objects.equals(serverId, tracker.serverId())) {
            clear();
            serverId = tracker.serverId();
            level = client.level;
        }
        String text = CrystalHollowsSidebar.stripFormatting(message.getString());
        if (!overlay) {
            if (isTreasureFound(text)) {
                clear();
                foundAtNanos = System.nanoTime();
            }
            return message;
        }
        if (client.player == null || tracker.lobby() == null) return message;
        double distance = MetalDetectorSolver.distance(text);
        if (!Double.isFinite(distance)) return message;
        if (ignorePostLootReading(distance, foundAtNanos, System.nanoTime())) return message;
        double x = client.player.getX(), y = detectorY(client), z = client.player.getZ();
        solver.accept(tracker.lobby().divanCentre(), x, y, z, distance, visibleChests(client),
                positionStability.stableAt(x, y, z));
        show(solver.candidates());
        if (solver.solved() && shouldAnnounce(shown.getFirst())) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.15f, 0.45f));
        }
        return message;
    }

    private static double detectorY(Minecraft client) {
        return client.player.getY() + client.player.getEyeHeight() - client.player.getEyeHeight(Pose.STANDING);
    }

    static boolean isTreasureFound(String text) {
        return text.startsWith("You found") && text.contains("with your Metal Detector");
    }

    static boolean ignorePostLootReading(double distance, long foundAt, long now) {
        return foundAt != 0 && now - foundAt < 1_000_000_000L && distance < 5;
    }

    private boolean shouldAnnounce(CrystalHollowsPosition position) {
        if (position.equals(announced)) return false;
        announced = position;
        return true;
    }

    private List<CrystalHollowsPosition> visibleChests(Minecraft client) {
        if (client.level == null) return List.of();
        Vec3 eye = client.player.getEyePosition();
        Vec3 direction = client.player.getViewVector(1.0f);
        List<CrystalHollowsPosition> visible = new ArrayList<>();
        HitResult aimed = client.level.clip(new ClipContext(eye, eye.add(direction.scale(64)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        addChest(client, aimed, visible);
        for (CrystalHollowsPosition position : solver.knownPositions()) {
            BlockPos block = new BlockPos(position.x(), position.y(), position.z());
            if (!client.level.hasChunk(block.getX() >> 4, block.getZ() >> 4)) continue;
            var state = client.level.getBlockState(block);
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) continue;
            Vec3 target = new Vec3(position.x() + 0.5, position.y() + 0.5, position.z() + 0.5);
            Vec3 offset = target.subtract(eye);
            if (offset.lengthSqr() > 64 * 64 || offset.normalize().dot(direction) < 0.5) continue;
            HitResult hit = client.level.clip(new ClipContext(eye, target,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            addChest(client, hit, visible);
        }
        return visible;
    }

    private static void addChest(Minecraft client, HitResult hit, List<CrystalHollowsPosition> visible) {
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return;
        var state = client.level.getBlockState(block.getBlockPos());
        if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) return;
        var pos = block.getBlockPos();
        CrystalHollowsPosition chest = new CrystalHollowsPosition(pos.getX(), pos.getY(), pos.getZ());
        if (!visible.contains(chest)) visible.add(chest);
    }

    private void show(List<CrystalHollowsPosition> positions) {
        boolean solved = solver.solved();
        if (shown.equals(positions) && shownSolved == solved) return;
        shownSolved = solved;
        shown = positions;
        if (positions.isEmpty()) {
            manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of());
            return;
        }
        String name = Component.translatable(solved
                ? "waypointer.crystal.metal_detector.treasure"
                : "waypointer.crystal.metal_detector.possible").getString();
        WaypointGroup group = new WaypointGroup(GROUP_ID, name, "crystal_hollows");
        group.setRuntimeOnly(true);
        group.setEnabled(true);
        group.setLoadMode(solved ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        group.setSkipAheadEnabled(false);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        // A first distance shell can contain hundreds of positions; keep its full
        // evidence in the solver while bounding temporary render geometry.
        for (CrystalHollowsPosition position : positions.stream().limit(128).toList()) {
            group.add(Waypoint.at(position.x(), position.y(), position.z()).withName(name)
                    .withColor(solved ? 0xFFD54F : 0xC0C0C0)
                    .withFlags(Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR));
        }
        manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of(group));
    }

    private void clear() {
        solver.reset();
        positionStability.reset();
        announced = null;
        foundAtNanos = 0;
        show(List.of());
    }
}
