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
    private static final double PRECISE_SMALL_SIZE = 1.0 / 16.0;
    private static final Component HELP_MOVE = Component.literal(
            "Left click to set the new position. Right click to exit.")
            .withStyle(ChatFormatting.AQUA);
    private static final Component HELP_MOVE_PRECISE = Component.literal(
            "Left click to place the small waypoint at the cursor, snapped to 1/16 blocks. Right click to exit.")
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

    /*[[AI-FN-DOC
Function:
start.
Purpose:
Start one-shot reposition mode for an existing waypoint from the route editor.
Why this exists:
Shift-left-clicking a waypoint row should close the editor, show an in-world placement preview, and commit the new position on the next left click.
When to use:
Use from GroupEditScreen when moving an existing waypoint. Do not use for creating new unnamed or named waypoints; use startAdd for those flows.
Inputs:
manager is the active group manager; config is the live config; group is the route containing the waypoint; waypointIndex is the index to move.
Outputs:
No return value. Creates an active reposition session when inputs are valid.
Side effects:
Mutates the static active session, closes the current screen, and shows help text in chat/overlay.
Failure modes:
Null manager/config/group or out-of-range waypointIndex return without changing active mode.
Important invariants:
Small subwaypoints with FLAG_SMALL_SUBWAYPOINT enter precise sixteenth-block mode; all other waypoints preserve the legacy full-block reposition behavior.
Internal logic:
Validate inputs, inspect the selected waypoint's flags, build a MOVE_EXISTING session with the precise mode flag, close the GUI, and show mode-specific help.
Pseudocode:
if manager/config/group invalid, return
if waypointIndex out of group range, return
waypoint = group.get(waypointIndex)
precise = waypoint is subwaypoint and has small flag
active = new Session(manager, config, group, waypointIndex, MOVE_EXISTING, precise)
close screen
show help
Implementation notes:
The session snapshots whether the move started as precise so later row/style mutations cannot change interaction semantics mid-placement.
AI self-check:
Verify normal route waypoints still start block mode and small subwaypoints start precise mode.
]]*/
    public static void start(ActiveGroupManager manager, WaypointerConfig config,
                             WaypointGroup group, int waypointIndex) {
        if (manager == null || config == null || group == null) return;
        if (waypointIndex < 0 || waypointIndex >= group.size()) return;

        Minecraft mc = Minecraft.getInstance();
        Waypoint waypoint = group.get(waypointIndex);
        boolean preciseSmallMove = waypoint.isSubwaypoint()
                && waypoint.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT);
        active = new Session(manager, config, group, waypointIndex,
                Mode.MOVE_EXISTING, preciseSmallMove);
        mc.setScreen(null);
        showHelp(mc);
    }

    /*[[AI-FN-DOC
Function:
startAdd.
Purpose:
Start one-shot placement mode for creating a new waypoint at a targeted block.
Why this exists:
Keybind add flows need the same safe click-to-place/cancel mechanics as repositioning without requiring the route editor screen to remain open.
When to use:
Use for add-current-target keybinds. Do not use for moving an existing waypoint.
Inputs:
manager is the active group manager; config is the live config; named selects whether placement opens the name prompt after picking a block.
Outputs:
No return value. Creates an add session when the client has a player and level.
Side effects:
May create or fetch the active group, mutate the static active session, close the current screen, and show help text.
Failure modes:
Null manager/config or missing player/level return without changing state.
Important invariants:
Add flows remain block-based; sixteenth-block precision is only for moving existing small waypoints.
Internal logic:
Validate inputs and client world, resolve the active route group, create an ADD_NAMED or ADD_UNNAMED session with precise mode disabled, close the GUI, and show help.
Pseudocode:
if manager or config null, return
mc = Minecraft instance
if no player or level, return
group = manager.getOrCreateActiveGroup(config skip setting)
active = new Session(manager, config, group, -1, add mode, false)
close screen
show help
Implementation notes:
Keeping add placement block-based avoids changing the semantics of existing keybinds.
AI self-check:
Verify named and unnamed add sessions pass preciseSmallMove=false.
]]*/
    public static void startAdd(ActiveGroupManager manager, WaypointerConfig config,
                                boolean named) {
        if (manager == null || config == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        WaypointGroup group = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        active = new Session(manager, config, group, -1,
                named ? Mode.ADD_NAMED : Mode.ADD_UNNAMED, false);
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

    /*[[AI-FN-DOC
Function:
commit.
Purpose:
Commit the currently active reposition/add session from the player's current target.
Why this exists:
The click callbacks need one place that turns the world hit result into a route mutation or named-waypoint prompt according to the active mode.
When to use:
Use from the intercepted left-click path while active is non-null. Do not call when no reposition/add session is running.
Inputs:
mc is the Minecraft client used to read the current hit result and reopen screens.
Outputs:
No return value. May complete, keep, or clear the active session.
Side effects:
Moves existing waypoints, adds unnamed waypoints, opens the named prompt, fires data-changed events through helpers, or re-shows help when no valid target exists.
Failure modes:
If there is no session or no valid hit target, returns without mutation and shows help for target failures.
Important invariants:
Precise small moves must use targetedPrecise and moveWaypointToPrecise; all add flows and normal moves must keep targetedBlock behavior.
Internal logic:
Snapshot active session, branch precise small move before block targeting, otherwise resolve the block target and dispatch by mode.
Pseudocode:
session = active
if session null, return
if session is precise small move:
  target = targetedPrecise(mc)
  if target null, show help and return
  moveExistingPrecise(mc, session, target)
  return
pos = targetedBlock(mc)
if pos null, show help and return
switch mode:
  MOVE_EXISTING -> moveExisting
  ADD_UNNAMED -> addUnnamed
  ADD_NAMED -> openNamedPrompt
Implementation notes:
The precise branch is intentionally limited to MOVE_EXISTING sessions started from a small waypoint row.
AI self-check:
Verify normal block reposition remains unchanged and precise failure does not clear active mode.
]]*/
    private static void commit(Minecraft mc) {
        Session session = active;
        if (session == null) return;

        if (session.preciseSmallMove()) {
            PreciseTarget target = targetedPrecise(mc);
            if (target == null) {
                showHelp(mc);
                return;
            }
            moveExistingPrecise(mc, session, target);
            return;
        }

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

    /*[[AI-FN-DOC
Function:
moveExisting.
Purpose:
Commit a whole-block reposition for an existing waypoint and reopen the editor.
Why this exists:
Normal waypoint movement is still block-oriented and should share the old moveWaypointTo path and UI return behavior.
When to use:
Use from commit for MOVE_EXISTING sessions that are not precise small moves.
Inputs:
mc is the Minecraft client used to schedule reopening; session is the active move session; pos is the targeted block position.
Outputs:
No return value.
Side effects:
Mutates the route waypoint position, fires data changed, clears active mode, and reopens the group editor focused on the moved waypoint.
Failure modes:
If the stored waypoint index is no longer valid, clears active mode and returns without route mutation.
Important invariants:
Block moves reset sub-block precision to the center of the selected block through WaypointGroup.moveWaypointTo.
Internal logic:
Validate the waypoint index, call group.moveWaypointTo with pos x/y/z, fire data changed, clear active, and reopen the editor.
Pseudocode:
if waypoint index invalid:
  active = null
  return
group.moveWaypointTo(index, pos x, pos y, pos z)
manager.fireDataChanged()
active = null
reopen editor
Implementation notes:
This remains separate from moveExistingPrecise so the two movement semantics cannot accidentally mix.
AI self-check:
Verify this method still matches the previous full-block reposition behavior.
]]*/
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

    /*[[AI-FN-DOC
Function:
moveExistingPrecise.
Purpose:
Commit a sixteenth-block precise reposition for an existing small waypoint and reopen the editor.
Why this exists:
Small subwaypoints are visually tiny, so moving them by full blocks makes placement feel disconnected from the marker the user is actually editing.
When to use:
Use only for MOVE_EXISTING sessions whose preciseSmallMove flag is true.
Inputs:
mc is the Minecraft client used to schedule reopening; session is the active move session; target contains snapped absolute sixteenth-block center coordinates.
Outputs:
No return value.
Side effects:
Mutates the route waypoint's precise center, fires data changed, clears active mode, and reopens the group editor focused on the moved waypoint.
Failure modes:
If the stored waypoint index is invalid, clears active mode and returns without mutation.
Important invariants:
Only the waypoint position changes; flags, color, radius, temp metadata, and route order are preserved by withPreciseSixteenths.
Internal logic:
Validate the waypoint index, call group.moveWaypointToPrecise with the snapped target, fire data changed, clear active, and reopen the editor.
Pseudocode:
if waypoint index invalid:
  active = null
  return
group.moveWaypointToPrecise(index, target precise x/y/z)
manager.fireDataChanged()
active = null
reopen editor
Implementation notes:
This mirrors moveExisting's lifecycle but stores precise sixteenths instead of integer block coordinates.
AI self-check:
Verify the editor reopens on the same waypoint and the saved center matches the preview target.
]]*/
    private static void moveExistingPrecise(Minecraft mc, Session session, PreciseTarget target) {
        if (session.waypointIndex < 0 || session.waypointIndex >= session.group.size()) {
            active = null;
            return;
        }

        session.group.moveWaypointToPrecise(session.waypointIndex,
                target.preciseX(), target.preciseY(), target.preciseZ());
        session.manager.fireDataChanged();
        active = null;
        reopenEditor(mc, session);
    }

    private static void addUnnamed(Minecraft mc, Session session, BlockPos pos) {
        session.group.add(new Waypoint(pos.getX(), pos.getY(), pos.getZ(),
                "", session.config.defaultWaypointColor(), 0, 0.0));
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

    /*[[AI-FN-DOC
Function:
renderOutline.
Purpose:
Render the active reposition/add preview in the world.
Why this exists:
Users need visual confirmation of what the next left click will commit, especially now that small waypoint movement can target sixteenth-block centers rather than whole blocks.
When to use:
Registered on WorldRenderEvents.END_MAIN and called while active mode may be non-null.
Inputs:
ctx is the world render context supplying matrices and buffer consumers.
Outputs:
No return value. Emits line-box geometry when there is an active session and a valid target.
Side effects:
Writes preview vertices into the line render buffer.
Failure modes:
Missing active session, hit target, pose stack, buffers, or camera position causes an early return without rendering.
Important invariants:
Precise small sessions draw a 1/16-block cube centered at targetedPrecise; all other sessions draw the old full-block wireframe.
Internal logic:
Resolve the active session and render dependencies, branch to precise or block target math, compute camera-relative bounds, emit the line box, and end the batch.
Pseudocode:
if no active session, return
resolve minecraft, pose stack, buffers
if precise small mode:
  target = targetedPrecise
  if target null, return
  bounds = target center +/- PRECISE_SMALL_SIZE / 2
else:
  pos = targetedBlock
  if pos null, return
  bounds = full block pos to pos + 1
subtract camera and OUTLINE_EXPAND from bounds
emit line box
end batch
Implementation notes:
The preview size duplicates the renderer's small waypoint size intentionally so reposition mode stays independent from private renderer constants.
AI self-check:
Verify full-block preview remains identical for normal waypoints and add flows.
]]*/
    private static void renderOutline(WorldRenderContext ctx) {
        if (active == null) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = ctx.matrices();
        if (ps == null) return;
        var buffers = ctx.consumers();
        if (buffers == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;

        if (active.preciseSmallMove()) {
            PreciseTarget target = targetedPrecise(mc);
            if (target == null) return;
            double cx = target.x();
            double cy = target.y();
            double cz = target.z();
            double half = PRECISE_SMALL_SIZE * 0.5;
            minX = cx - half;
            minY = cy - half;
            minZ = cz - half;
            maxX = cx + half;
            maxY = cy + half;
            maxZ = cz + half;
        } else {
            BlockPos pos = targetedBlock(mc);
            if (pos == null) return;
            minX = pos.getX();
            minY = pos.getY();
            minZ = pos.getZ();
            maxX = pos.getX() + 1.0;
            maxY = pos.getY() + 1.0;
            maxZ = pos.getZ() + 1.0;
        }

        float x1 = (float) (minX - cam.x - OUTLINE_EXPAND);
        float y1 = (float) (minY - cam.y - OUTLINE_EXPAND);
        float z1 = (float) (minZ - cam.z - OUTLINE_EXPAND);
        float x2 = (float) (maxX - cam.x + OUTLINE_EXPAND);
        float y2 = (float) (maxY - cam.y + OUTLINE_EXPAND);
        float z2 = (float) (maxZ - cam.z + OUTLINE_EXPAND);

        var type = WaypointerRenderPipelines.linesThroughWalls();
        VertexConsumer lines = buffers.getBuffer(type);
        RenderHelpers.emitLineBox(lines, ps, x1, y1, z1, x2, y2, z2,
                OUTLINE_COLOR, OUTLINE_ALPHA, OUTLINE_WIDTH);
        RenderHelpers.endBatch(buffers, type);
    }

    /*[[AI-FN-DOC
Function:
targetedPrecise.
Purpose:
Convert the current block hit location into a snapped sixteenth-block target.
Why this exists:
Small waypoint repositioning should follow the cursor hit point on the block face instead of collapsing to the block's integer position.
When to use:
Use only for precise small move sessions in commit and renderOutline.
Inputs:
mc is the Minecraft client whose hitResult is inspected; it may be null or not currently targeting a block.
Outputs:
Returns a PreciseTarget with absolute sixteenth-block coordinates, or null when no block hit is available.
Side effects:
None.
Failure modes:
Null client, non-block hit results, or miss/entity hits return null.
Important invariants:
All returned coordinates are snapped with Waypoint.snapToPreciseSixteenths so preview and commit agree exactly.
Internal logic:
Validate that the current hit result is a block hit, read its exact Vec3 location, snap each axis to sixteenths, and return the target.
Pseudocode:
if mc null or hitResult not BlockHitResult, return null
if hit type is not BLOCK, return null
loc = hit location
return PreciseTarget(snap loc.x, snap loc.y, snap loc.z)
Implementation notes:
BlockHitResult location is used instead of getBlockPos so the user can place the tiny marker on sub-block details.
AI self-check:
Verify commit and preview both call this helper rather than duplicating snap math.
]]*/
    private static PreciseTarget targetedPrecise(Minecraft mc) {
        if (mc == null || !(mc.hitResult instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        Vec3 location = hit.getLocation();
        return new PreciseTarget(
                Waypoint.snapToPreciseSixteenths(location.x),
                Waypoint.snapToPreciseSixteenths(location.y),
                Waypoint.snapToPreciseSixteenths(location.z));
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

    private record PreciseTarget(int preciseX, int preciseY, int preciseZ) {
        /*[[AI-FN-DOC
Function:
PreciseTarget.x.
Purpose:
Convert the snapped precise X coordinate back into a world-space double for preview rendering.
Why this exists:
The preview renderer needs real world coordinates, while the committed target is stored as integer tenths.
When to use:
Use when drawing the precise small waypoint preview bounds. Do not use for storage, which should keep preciseX.
Inputs:
None.
Outputs:
Returns preciseX divided by Waypoint.PRECISE_SCALE.
Side effects:
None.
Failure modes:
None.
Important invariants:
The value must match the center that moveWaypointToPrecise will commit.
Internal logic:
Divide preciseX by the shared precision scale.
Pseudocode:
return preciseX / Waypoint.PRECISE_SCALE as double
Implementation notes:
Keeping conversion on the target record avoids repeating scale math in renderOutline.
AI self-check:
Verify this method remains a pure conversion.
]]*/
        double x() {
            return preciseX / (double) Waypoint.PRECISE_SCALE;
        }

        /*[[AI-FN-DOC
Function:
PreciseTarget.y.
Purpose:
Convert the snapped precise Y coordinate back into a world-space double for preview rendering.
Why this exists:
Precise small waypoint previews need vertical placement to match the committed tenth-block target.
When to use:
Use when drawing the precise preview cube's vertical bounds. Do not use for block-level placement.
Inputs:
None.
Outputs:
Returns preciseY divided by Waypoint.PRECISE_SCALE.
Side effects:
None.
Failure modes:
None.
Important invariants:
The value must match the vertical center saved through moveWaypointToPrecise.
Internal logic:
Divide preciseY by the shared precision scale.
Pseudocode:
return preciseY / Waypoint.PRECISE_SCALE as double
Implementation notes:
This mirrors x and z for readability at render call sites.
AI self-check:
Verify this method remains a pure conversion.
]]*/
        double y() {
            return preciseY / (double) Waypoint.PRECISE_SCALE;
        }

        /*[[AI-FN-DOC
Function:
PreciseTarget.z.
Purpose:
Convert the snapped precise Z coordinate back into a world-space double for preview rendering.
Why this exists:
The preview cube needs the same precise center on every axis that commit will save.
When to use:
Use when drawing precise preview bounds. Do not use for JSON persistence, which stores preciseZ directly.
Inputs:
None.
Outputs:
Returns preciseZ divided by Waypoint.PRECISE_SCALE.
Side effects:
None.
Failure modes:
None.
Important invariants:
The value must match the committed Z center exactly after scale conversion.
Internal logic:
Divide preciseZ by the shared precision scale.
Pseudocode:
return preciseZ / Waypoint.PRECISE_SCALE as double
Implementation notes:
This keeps renderOutline free from raw scale arithmetic for each axis.
AI self-check:
Verify this method remains a pure conversion.
]]*/
        double z() {
            return preciseZ / (double) Waypoint.PRECISE_SCALE;
        }
    }

    private record Session(ActiveGroupManager manager, WaypointerConfig config,
                           WaypointGroup group, int waypointIndex, Mode mode,
                           boolean preciseSmallMove) {
        /*[[AI-FN-DOC
Function:
Session.withWaypointIndex.
Purpose:
Return a copy of the active session focused on a different waypoint index.
Why this exists:
Add flows do not know the new waypoint index until after insertion, but reopening the editor needs the completed index in the session.
When to use:
Use after adding a waypoint and before reopening the editor. Do not use to switch a move session to a different route group.
Inputs:
index is the waypoint index that should be focused after the operation.
Outputs:
Returns a new Session with index substituted and all other session state preserved.
Side effects:
None.
Failure modes:
None.
Important invariants:
The preciseSmallMove flag must be preserved so copying a session cannot silently change placement mode.
Internal logic:
Construct a new Session with the same manager, config, group, mode, and precise flag but the supplied waypoint index.
Pseudocode:
return new Session(manager, config, group, index, mode, preciseSmallMove)
Implementation notes:
This is primarily used by addUnnamed, where preciseSmallMove is always false.
AI self-check:
Verify all session fields except waypointIndex are unchanged.
]]*/
        Session withWaypointIndex(int index) {
            return new Session(manager, config, group, index, mode, preciseSmallMove);
        }

        /*[[AI-FN-DOC
Function:
Session.help.
Purpose:
Return the overlay/chat help text for the active session.
Why this exists:
Move, precise move, unnamed add, and named add modes have slightly different user intent and need accurate instructions.
When to use:
Use from showHelp whenever a session is active.
Inputs:
None.
Outputs:
Returns the Component that should be displayed to the user.
Side effects:
None.
Failure modes:
None.
Important invariants:
Precise small move sessions must mention 0.1-block snapping; normal move sessions keep the old wording.
Internal logic:
If mode is MOVE_EXISTING and preciseSmallMove is true, return HELP_MOVE_PRECISE; otherwise switch over mode for the standard messages.
Pseudocode:
if mode == MOVE_EXISTING and preciseSmallMove return precise help
switch mode:
  MOVE_EXISTING -> HELP_MOVE
  ADD_UNNAMED -> HELP_ADD
  ADD_NAMED -> HELP_ADD_NAMED
Implementation notes:
The explicit pre-check keeps the enum unchanged and avoids adding a separate mode that would duplicate move semantics.
AI self-check:
Verify every mode returns a non-null component.
]]*/
        Component help() {
            if (mode == Mode.MOVE_EXISTING && preciseSmallMove) return HELP_MOVE_PRECISE;
            return switch (mode) {
                case MOVE_EXISTING -> HELP_MOVE;
                case ADD_UNNAMED -> HELP_ADD;
                case ADD_NAMED -> HELP_ADD_NAMED;
            };
        }
    }
}
