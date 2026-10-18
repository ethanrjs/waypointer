package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.CompassServerMessage;
import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import com.babbur.waypointer.crystal.compass.Vec3d;
import com.babbur.waypointer.crystal.compass.WishingCompassSolver;
import com.babbur.waypointer.crystal.compass.WishingCompassTarget;
import com.babbur.waypointer.dungeon.DungeonItemIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class WishingCompassController {

    private static final String COMPASS_ID = "WISHING_COMPASS";
    private static final String JUNGLE_KEY_ID = "JUNGLE_KEY";

    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;
    private final WishingCompassSolver solver = new WishingCompassSolver(System::currentTimeMillis);
    private String lastEvent = "none";
    private boolean enabledLastTick;

    public WishingCompassController(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this.tracker = tracker;
        this.config = config;
        this.enabledLastTick = config.crystalHollowsWishingCompassSolver();
    }

    public void install() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()
                    && tracker.active()
                    && config.crystalHollowsWishingCompassSolver()) {
                ItemStack stack = player.getItemInHand(hand);
                if (isWishingCompass(stack)) {
                    tracker.observeCompassPosition(player.getX(), player.getY(), player.getZ());
                    prepareTargetContext(player.getInventory());
                    solver.onUse(player.getX(), player.getY(), player.getZ(),
                            CrystalHollowsGeometry.zoneAt(
                                    player.getX(), player.getY(), player.getZ()),
                            System.currentTimeMillis());
                    handleOutcomes();
                }
            }
            return InteractionResult.PASS;
        });
        CrystalHollowsParticleHook.setListener(this::onParticle);
        tracker.attachCompassController(this);
    }

    public WishingCompassSolver solver() { return solver; }
    public String lastEvent() { return lastEvent; }

    public void tick(long nowMillis) {
        if (!config.crystalHollowsWishingCompassSolver()) {
            disableIfNeeded();
            return;
        }
        enabledLastTick = true;
        solver.tick(nowMillis);
        handleOutcomes();
    }

    public void onServerMessage(CompassServerMessage message) {
        if (!config.crystalHollowsWishingCompassSolver()) {
            disableIfNeeded();
            return;
        }
        if (message == CompassServerMessage.NO_TARGET) {
            solver.serverNoTarget();
            handleOutcomes();
        }
    }

    public void reset() {
        solver.reset();
        if (tracker != null) tracker.clearCompassTarget();
        lastEvent = "reset";
    }

    void onRegionChanged(CrystalHollowsZone from, CrystalHollowsZone to) {
        if (from != null && to != null && WishingCompassSolver.zonesCompatible(from, to)) return;
        if (solver.lastResult().isEmpty()) solver.reset();
        lastEvent = "region_changed";
    }

    private void disableIfNeeded() {
        if (enabledLastTick
                || solver.state() != WishingCompassSolver.State.IDLE
                || !solver.completedRays().isEmpty()) {
            reset();
        }
        enabledLastTick = false;
    }

    private void onParticle(double x, double y, double z, int count) {
        if (!tracker.active() || !config.crystalHollowsWishingCompassSolver()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        tracker.observeCompassPosition(player.getX(), player.getY(), player.getZ());
        solver.onParticle(x, y, z, System.currentTimeMillis());
        handleOutcomes();
    }

    private void prepareTargetContext(Inventory inventory) {
        Map<Crystal, CrystalState> crystals = tracker.crystalStatesForCompass();
        solver.setTargetContext(crystals, containsItem(inventory, JUNGLE_KEY_ID, "Jungle Key"),
                tracker.hasKingsScent());
    }

    private static boolean containsItem(Inventory inventory, String id, String fallbackName) {
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (DungeonItemIdentity.hasSkyBlockId(stack, id)
                    || stack.getHoverName().getString().contains(fallbackName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWishingCompass(ItemStack stack) {
        return DungeonItemIdentity.hasSkyBlockId(stack, COMPASS_ID)
                || stack != null && !stack.isEmpty()
                && stack.getHoverName().getString().contains("Wishing Compass");
    }

    private void handleOutcomes() {
        for (WishingCompassSolver.Outcome outcome : solver.drainOutcomes()) {
            lastEvent = outcome.event().name().toLowerCase(java.util.Locale.ROOT);
            Waypointer.LOGGER.debug("Wishing Compass solver event: {}", outcome.event());
            switch (outcome.event()) {
                case USE_RECORDED, RAY_CAPTURED -> {}
                case TOO_CLOSE -> send("waypointer.crystal.compass.too_close");
                case NEED_SECOND_USE -> send("waypointer.crystal.compass.need_second");
                case NEARLY_PARALLEL -> send("waypointer.crystal.compass.parallel");
                case INVALID_BEHIND_RAY -> send("waypointer.crystal.compass.behind");
                case INVALID_OUTSIDE_HOLLOWS -> send("waypointer.crystal.compass.outside");
                case INVALID_LARGE_GAP -> send("waypointer.crystal.compass.large_gap");
                case NO_PARTICLES -> send("waypointer.crystal.compass.no_particles");
                case TIMEOUT -> send("waypointer.crystal.compass.timeout");
                case TOO_SHORT -> send("waypointer.crystal.compass.too_short");
                case NO_TARGET -> send("waypointer.crystal.compass.no_target");
                case SOLVED -> handleSolved(outcome.result());
            }
        }
    }

    private void handleSolved(WishingCompassSolver.SolveResult result) {
        if (result == null) return;
        Waypointer.LOGGER.info("Wishing Compass solved at {}, {}, {} with gap {} and targets {}",
                result.solution().x(), result.solution().y(), result.solution().z(),
                result.gap(), result.targets());
        Set<WishingCompassTarget> targets = result.targets();
        StructureSighting sighting = toSighting(result);
        CrystalHollowsZone originRegion = result.firstRay().zone() == result.secondRay().zone()
                ? result.searchZone() : null;
        if (targets.size() == 1 && targets.contains(WishingCompassTarget.CRYSTAL_NUCLEUS)) {
            tracker.focusCompassTarget(sighting, originRegion);
            send(Component.translatable("waypointer.crystal.compass.solved_nucleus").append(CrystalHollowsShare.defocus()));
            return;
        }
        tracker.merge(sighting, false);
        tracker.focusCompassTarget(sighting, originRegion);
        sighting = tracker.compassTargetSighting();
        Component names = sighting.structure() == CrystalHollowsStructure.WISHING_TARGET
                ? targetNames(targets)
                : Component.translatable("waypointer.crystal.structure." + sighting.structure().id())
                    .withStyle(Style.EMPTY.withColor(sighting.structure().rgb()));
        MutableComponent message = Component.translatable("waypointer.crystal.compass.solved",
                names,
                coordinate(sighting.x()), coordinate(sighting.y()), coordinate(sighting.z()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" "))
                .append(Component.translatable("waypointer.crystal.action.share")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(CrystalHollowsShare.share(sighting))))
                .append(CrystalHollowsShare.defocus());
        send(message);
    }

    private static Component targetNames(Set<WishingCompassTarget> targets) {
        if (targets.isEmpty()) {
            return Component.translatable("waypointer.crystal.compass.unknown");
        }
        MutableComponent names = Component.empty();
        boolean first = true;
        for (WishingCompassTarget target : targets) {
            if (!first) names.append(Component.literal(" / "));
            names.append(Component.translatable(
                    "waypointer.crystal.structure." + target.structure().id())
                    .withStyle(Style.EMPTY.withColor(target.structure().rgb())));
            first = false;
        }
        return names;
    }

    private static StructureSighting toSighting(WishingCompassSolver.SolveResult result) {
        Vec3d solution = result.solution();
        int x = (int) Math.floor(solution.x());
        int y = (int) Math.floor(solution.y());
        int z = (int) Math.floor(solution.z());
        List<CrystalHollowsStructure> candidates = new ArrayList<>();
        for (WishingCompassTarget target : result.targets()) candidates.add(target.structure());
        CrystalHollowsStructure structure = candidates.size() == 1
                ? candidates.getFirst()
                : CrystalHollowsStructure.WISHING_TARGET;
        String note = "";
        if (structure == CrystalHollowsStructure.JUNGLE_TEMPLE) {
            note = "crystal at " + x + " " + y + " " + z;
            x -= 57;
            y += 36;
            z -= 21;
        }
        return new StructureSighting(structure, x, y, z, SightingConfidence.COMPASS,
                "compass", System.currentTimeMillis(), candidates, note);
    }

    private static void send(String translationKey) {
        send(Component.translatable(translationKey).withStyle(ChatFormatting.GRAY));
    }

    private static Component coordinate(int value) {
        return Component.literal(Integer.toString(value)).withStyle(ChatFormatting.WHITE);
    }

    private static void send(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(WaypointerChatFeedback.suppress(Component.literal("[WP] ")
                    .withStyle(ChatFormatting.AQUA).append(message)));
        }
    }
}
