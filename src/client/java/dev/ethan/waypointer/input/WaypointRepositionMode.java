package dev.ethan.waypointer.input;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ethan.waypointer.chat.WaypointerChatFeedback;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.render.RenderHelpers;
import dev.ethan.waypointer.render.WaypointerRenderPipelines;
import dev.ethan.waypointer.screen.AddNamedWaypointScreen;
import dev.ethan.waypointer.screen.GroupEditScreen;
import dev.ethan.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One-shot "pick a block in the world" mode for waypoint edits.
 *
 * <p>The editor starts this from Shift + left-click on a waypoint row, and keybinds
 * can start the same picker for creating new waypoints at the targeted block. While
 * active we close the GUI, outline the currently-targeted block, intercept left
 * click to commit, and intercept right click to cancel without placing or using
 * items.
 */
public final class WaypointRepositionMode {

    private static final int OUTLINE_COLOR = 0x4FB3C4;
    private static final float OUTLINE_ALPHA = 0.95f;
    private static final float OUTLINE_WIDTH = 4.0f;
    private static final double OUTLINE_EXPAND = 0.002;
    private static final Component HELP_MOVE = Component.literal(
            "Left click to set the new position. Right click to exit.")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_ADD = Component.literal(
            "Left click to place the waypoint. Right click to exit.")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_ADD_NAMED = Component.literal(
            "Left click to place the named waypoint. Right click to exit.")
            .withStyle(ChatFormatting.AQUA);

    private static Session active;

    private WaypointRepositionMode() {}

    public static void install() {
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (active == null) return false;
            if (clickCount == 0) return true;
            commit(client);
            return true;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (active == null || !world.isClientSide()) return InteractionResult.PASS;
            cancel(Minecraft.getInstance());
            return InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (active == null || !world.isClientSide()) return InteractionResult.PASS;
            cancel(Minecraft.getInstance());
            return InteractionResult.FAIL;
        });
        ClientTickEvents.END_CLIENT_TICK.register(WaypointRepositionMode::onTick);
        WorldRenderEvents.END_MAIN.register(WaypointRepositionMode::renderOutline);
    }

    public static void start(ActiveGroupManager manager, WaypointerConfig config,
                             WaypointGroup group, int waypointIndex) {
        if (manager == null || config == null || group == null) return;
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;

        Minecraft mc = Minecraft.getInstance();
        active = new Session(manager, config, group, waypointIndex, Mode.MOVE_EXISTING);
        mc.setScreen(null);
        showHelp(mc);
    }

    public static void startAdd(ActiveGroupManager manager, WaypointerConfig config,
                                boolean named) {
        if (manager == null || config == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        WaypointGroup group = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        active = new Session(manager, config, group, -1,
                named ? Mode.ADD_NAMED : Mode.ADD_UNNAMED);
        mc.setScreen(null);
        showHelp(mc);
    }

    private static void onTick(Minecraft mc) {
        if (active == null) return;
        if (mc.player == null || mc.level == null) {
            active = null;
            return;
        }
        if (mc.screen != null) {
            active = null;
            return;
        }
    }

    private static void commit(Minecraft mc) {
        Session session = active;
        if (session == null) return;

        BlockPos pos = targetedBlock(mc);
        if (pos == null) {
            showHelp(mc);
            return;
        }

        switch (session.mode) {
            case MOVE_EXISTING -> moveExisting(mc, session, pos);
            case ADD_UNNAMED -> addUnnamed(mc, session, pos);
            case ADD_NAMED -> openNamedPrompt(mc, session, pos);
        }
    }

    private static void moveExisting(Minecraft mc, Session session, BlockPos pos) {
        if (session.waypointIndex < 0 || session.waypointIndex >= session.group.size()) {
            active = null;
            return;
        }

        session.group.moveWaypointTo(session.waypointIndex,
                pos.getX(), pos.getY(), pos.getZ());
        session.manager.fireDataChanged();
        active = null;
        reopenEditor(mc, session);
    }

    private static void addUnnamed(Minecraft mc, Session session, BlockPos pos) {
        session.group.add(new Waypoint(pos.getX(), pos.getY(), pos.getZ(),
                "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        int index = session.group.size() - 1;
        new WaypointAddFlow().afterWaypointAdded(session.group, index);
        session.manager.fireDataChanged();
        active = null;
        reopenEditor(mc, session.withWaypointIndex(index));
    }

    private static void openNamedPrompt(Minecraft mc, Session session, BlockPos pos) {
        active = null;
        mc.execute(() -> AddNamedWaypointScreen.openAt(null, session.manager, session.config,
                session.group, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void cancel(Minecraft mc) {
        active = null;
        if (mc != null && mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal("Reposition mode cancelled.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    private static void reopenEditor(Minecraft mc, Session session) {
        if (mc == null) return;
        mc.execute(() -> {
            WaypointerScreen parent = new WaypointerScreen(session.manager, session.config);
            GroupEditScreen.openFocused(parent, session.manager, session.config,
                    session.group, session.waypointIndex);
        });
    }

    private static void showHelp(Minecraft mc) {
        if (mc == null) return;
        Component help = active == null ? HELP_MOVE : active.help();
        if (mc.player != null) {
            mc.player.displayClientMessage(WaypointerChatFeedback.suppress(help), false);
        }
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(help, false);
        }
    }

    private static void renderOutline(WorldRenderContext ctx) {
        if (active == null) return;

        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = targetedBlock(mc);
        if (pos == null) return;

        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        var buffers = ctx.consumers();
        if (buffers == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        float x1 = (float) (pos.getX() - cam.x - OUTLINE_EXPAND);
        float y1 = (float) (pos.getY() - cam.y - OUTLINE_EXPAND);
        float z1 = (float) (pos.getZ() - cam.z - OUTLINE_EXPAND);
        float x2 = (float) (pos.getX() + 1.0 - cam.x + OUTLINE_EXPAND);
        float y2 = (float) (pos.getY() + 1.0 - cam.y + OUTLINE_EXPAND);
        float z2 = (float) (pos.getZ() + 1.0 - cam.z + OUTLINE_EXPAND);

        var type = WaypointerRenderPipelines.linesThroughWalls();
        VertexConsumer lines = buffers.getBuffer(type);
        RenderHelpers.emitLineBox(lines, ps, x1, y1, z1, x2, y2, z2,
                OUTLINE_COLOR, OUTLINE_ALPHA, OUTLINE_WIDTH);
        RenderHelpers.endBatch(buffers, type);
    }

    private static BlockPos targetedBlock(Minecraft mc) {
        if (mc == null || !(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return hit.getBlockPos();
    }

    private enum Mode {
        MOVE_EXISTING,
        ADD_UNNAMED,
        ADD_NAMED
    }

    private record Session(ActiveGroupManager manager, WaypointerConfig config,
                           WaypointGroup group, int waypointIndex, Mode mode) {
        Session withWaypointIndex(int index) {
            return new Session(manager, config, group, index, mode);
        }

        Component help() {
            return switch (mode) {
                case MOVE_EXISTING -> HELP_MOVE;
                case ADD_UNNAMED -> HELP_ADD;
                case ADD_NAMED -> HELP_ADD_NAMED;
            };
        }
    }
}
