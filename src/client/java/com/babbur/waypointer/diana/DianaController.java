package com.babbur.waypointer.diana;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CrystalHollowsSidebar;
import com.babbur.waypointer.crystal.compass.Vec3d;
import com.babbur.waypointer.dungeon.DungeonItemIdentity;
import com.babbur.waypointer.location.SidebarTexts;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Local, Hub-only Diana evidence rendered using Waypointer's temporary markers. */
public final class DianaController {
    public static final String GROUP_ID = "diana:burrows";
    private static DianaController installed;
    private final ActiveGroupManager manager;
    private final BooleanSupplier enabled;
    private final DianaBurrows burrows = new DianaBurrows();
    private List<DianaBurrows.Marker> shown = List.of();
    private Object level;
    private String serverId;

    public DianaController(ActiveGroupManager manager, BooleanSupplier enabled) {
        this.manager = manager;
        this.enabled = enabled;
    }

    public void install() {
        installed = this;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        manager.addZoneListener(zone -> {
            if (zone == null || !"hub".equals(zone.id())) reset();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active()) return;
            String currentServer = CrystalHollowsSidebar.serverId(SidebarTexts.collectColorStripped(client));
            if (currentServer != null) {
                if (serverId != null && !serverId.equals(currentServer)) reset();
                serverId = currentServer;
            }
            burrows.expire(nowMillis());
            show();
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() && active() && isSpade(player.getItemInHand(hand))) use(player);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() && active() && isSpade(player.getItemInHand(hand))) {
                burrows.click(block(hit.getBlockPos()), nowMillis());
                use(player);
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() && active() && isSpade(player.getItemInHand(hand))) {
                burrows.click(block(pos), nowMillis());
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && active()) {
                burrows.message(message.getString().replaceAll("\u00a7.", ""), nowMillis());
                show();
            }
        });
    }

    private void use(Player player) {
        burrows.use(new Vec3d(player.getX(), player.getY(), player.getZ()), nowMillis());
        show();
    }

    private static boolean isSpade(ItemStack stack) {
        return DungeonItemIdentity.hasSkyBlockId(stack, "ANCESTRAL_SPADE");
    }

    private boolean active() {
        Minecraft client = Minecraft.getInstance();
        boolean active = enabled.getAsBoolean() && client.player != null && client.level != null
                && manager.currentZone() != null && "hub".equals(manager.currentZone().id());
        if (!active || level != client.level) reset();
        level = client.level;
        return active;
    }

    /** Called at the tail of the existing client packet listener, on the client thread. */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        DianaController controller = installed;
        if (controller == null) return;
        var particle = packet.getParticle().getType();
        DianaBurrows.Particle type;
        if (particle == ParticleTypes.ENCHANTED_HIT) type = DianaBurrows.Particle.ENCHANTED_HIT;
        else if (particle == ParticleTypes.CRIT) type = DianaBurrows.Particle.CRIT;
        else if (particle == ParticleTypes.DRIPPING_LAVA) type = DianaBurrows.Particle.DRIPPING_LAVA;
        else if (particle == ParticleTypes.LARGE_SMOKE) type = DianaBurrows.Particle.LARGE_SMOKE;
        else return;
        if (!controller.active()) return;
        controller.burrows.particle(type, packet.getX(), packet.getY(), packet.getZ(), packet.getCount(),
                packet.getMaxSpeed(), packet.getXDist(), packet.getYDist(), packet.getZDist(),
                nowMillis());
        // Rendering is updated once at the end of the tick, not once per particle.
    }

    private void show() {
        List<DianaBurrows.Marker> markers = burrows.markers();
        if (shown.equals(markers)) return;
        shown = markers;
        if (markers.isEmpty()) {
            manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of());
            return;
        }
        WaypointGroup group = new WaypointGroup(GROUP_ID,
                Component.translatable("waypointer.diana.burrows").getString(), "hub");
        group.setRuntimeOnly(true);
        group.setTemp(true); // Event evidence must never advance just from standing nearby.
        group.setEnabled(true);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setSkipAheadEnabled(false);
        for (DianaBurrows.Marker marker : markers) {
            String key = switch (marker.kind()) {
                case START -> "start";
                case MOB -> "mob";
                case TREASURE -> "treasure";
                case ESTIMATE -> "estimate";
            };
            int color = switch (marker.kind()) {
                case START -> 0x70DFC1;
                case MOB -> 0xF08D83;
                case TREASURE -> 0xF4CE76;
                case ESTIMATE -> 0x94BFF2;
            };
            var block = marker.block();
            group.add(Waypoint.at(block.x(), block.y(), block.z())
                    .withName(Component.translatable("waypointer.diana." + key).getString())
                    .withColor(color)
                    .withFlags(Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR)
                    .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0));
        }
        manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of(group));
    }

    public void reset() {
        burrows.reset();
        level = null;
        serverId = null;
        show();
    }

    private static DianaBurrows.Block block(BlockPos pos) {
        return new DianaBurrows.Block(pos.getX(), pos.getY(), pos.getZ());
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000;
    }
}
