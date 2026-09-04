package com.babbur.waypointer.crystal;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Connects visible compass use/particle signals to the Minecraft-free solver. */
public final class WishingCompassController {

    private static final String COMPASS_ID = "WISHING_COMPASS";
    private static final String JUNGLE_KEY_ID = "JUNGLE_KEY";

    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;
    private final WishingCompassSolver solver = new WishingCompassSolver(System::currentTimeMillis);
    private final WishingCompassRayRenderer renderer;

    public WishingCompassController(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this.tracker = tracker;
        this.config = config;
        this.renderer = new WishingCompassRayRenderer(config);
    }

    public void install() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()
                    && tracker.active()
                    && config.crystalHollowsWishingCompassSolver()) {
                ItemStack stack = player.getItemInHand(hand);
                if (isWishingCompass(stack)) {
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
        renderer.install();
    }

    public WishingCompassSolver solver() { return solver; }

    public void tick(long nowMillis) {
        solver.tick(nowMillis);
        handleOutcomes();
    }

    public void onServerMessage(CompassServerMessage message) {
        if (message == CompassServerMessage.NO_TARGET) {
            solver.serverNoTarget();
            handleOutcomes();
        }
    }

    public void reset() {
        solver.reset();
        renderer.clear();
    }

    private void onParticle(double x, double y, double z, int count) {
        if (!tracker.active() || !config.crystalHollowsWishingCompassSolver()) return;
        solver.onParticle(x, y, z, System.currentTimeMillis());
        handleOutcomes();
    }

    private void prepareTargetContext(Inventory inventory) {
        CrystalHollowsLobbyState lobby = tracker.lobby();
        Map<Crystal, CrystalState> crystals = lobby == null ? Map.of() : lobby.crystals();
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
            switch (outcome.event()) {
                case USE_RECORDED, RAY_CAPTURED -> {
                    if (outcome.event() == WishingCompassSolver.SolverEvent.RAY_CAPTURED) {
                        renderer.update(solver.completedRays(), null, 0L);
                    }
                }
                case NUCLEUS_WARNING -> send("waypointer.crystal.compass.nucleus_warning");
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
        renderer.update(solver.completedRays(), result.solution(), System.currentTimeMillis());
        Set<WishingCompassTarget> targets = result.targets();
        if (targets.size() == 1 && targets.contains(WishingCompassTarget.CRYSTAL_NUCLEUS)) {
            send("waypointer.crystal.compass.solved_nucleus");
            return;
        }
        StructureSighting sighting = toSighting(result);
        tracker.merge(sighting);
        String names = targets.isEmpty()
                ? Component.translatable("waypointer.crystal.compass.unknown").getString()
                : String.join(" / ", targets.stream()
                        .map(target -> target.structure().displayName()).toList());
        MutableComponent message = Component.translatable("waypointer.crystal.compass.solved",
                names, sighting.x(), sighting.y(), sighting.z(),
                String.format(java.util.Locale.ROOT, "%.1f", result.gap()))
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" "))
                .append(Component.translatable("waypointer.crystal.action.share")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/wpch share " + sighting.structure().id()))));
        send(message);
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
        send(Component.translatable(translationKey).withStyle(ChatFormatting.YELLOW));
    }

    private static void send(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
        }
    }
}
