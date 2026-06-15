package dev.ethan.waypointer.core;

/**
 * A single point in the world rendered by the mod.
 *
 * <p>Immutable by design: edits produce a new instance. This keeps the tick loop
 * safe to iterate while the UI mutates a group, and makes undo trivial to add later.
 *
 * <p>Color is 0xRRGGBB; alpha is controlled per-render by the renderer based on state
 * (completed / current / upcoming).
 *
 * <p>customRadius is in blocks. 0 means "use the group's defaultRadius".
 *
 * <p>Temporary waypoints carry a {@code tempMode} + {@code expiresAtMillis}:
 * <ul>
 *   <li>{@link #TEMP_NONE} — a normal, persisted waypoint.
 *   <li>{@link #TEMP_TIME} — removed once {@link System#currentTimeMillis()} passes {@code expiresAtMillis}.
 *   <li>{@link #TEMP_UNTIL_REACHED} — removed by the proximity tracker when it advances past this waypoint.
 *   <li>{@link #TEMP_UNTIL_LEAVE} — removed when the player leaves the server.
 * </ul>
 * All three temp modes are wiped on disconnect (Storage deliberately skips them
 * during save) so nothing ephemeral accumulates in the user's config file.
 */
public record Waypoint(
        int x,
        int y,
        int z,
        String name,
        int color,
        int flags,
        double customRadius,
        int tempMode,
        long expiresAtMillis,
        int preciseX,
        int preciseY,
        int preciseZ) {

    public static final int FLAG_HIDE_BEACON  = 1;
    public static final int FLAG_HIDE_NAME    = 1 << 1;
    public static final int FLAG_THROUGH_WALL = 1 << 2;
    public static final int FLAG_LOCKED_COLOR = 1 << 3; // excluded from gradient auto-recolor
    /** Structural flag: this waypoint is a one-level child of the nearest previous main waypoint. */
    public static final int FLAG_SUBWAYPOINT  = 1 << 4;
    /** Visual flag: subwaypoint renders as a 1/16 block cube centered in its block. */
    public static final int FLAG_SMALL_SUBWAYPOINT = 1 << 5;
    /** Visual flag: subwaypoint renders filled even when the global box style is outlined. */
    public static final int FLAG_FILLED_SUBWAYPOINT = 1 << 6;
    /** Visual flags that only make sense while {@link #FLAG_SUBWAYPOINT} is present. */
    public static final int SUBWAYPOINT_STYLE_FLAGS = FLAG_SMALL_SUBWAYPOINT | FLAG_FILLED_SUBWAYPOINT;
    /** Flags that define route structure and must survive even when visual flags are stripped from exports. */
    public static final int STRUCTURAL_FLAGS  = FLAG_SUBWAYPOINT;

    public static final int TEMP_NONE = 0;
    public static final int TEMP_TIME = 1;
    public static final int TEMP_UNTIL_REACHED = 2;
    public static final int TEMP_UNTIL_LEAVE = 3;

    public static final int DEFAULT_COLOR = 0x4FE05A; // bright green -- reads clearly against most biomes
    public static final int PRECISE_SCALE = 16;
    private static final int PRECISE_BLOCK_CENTER_OFFSET = PRECISE_SCALE / 2;

    /*[[AI-FN-DOC
Function:
Waypoint canonical record constructor.
Purpose:
Normalize waypoint names and keep block coordinates synchronized with the stored sixteenth-block center.
Why this exists:
Small subwaypoints can now carry sub-block precision while legacy block coordinates remain the public compatibility surface for codecs, commands, and UI fields.
When to use:
Called automatically by every Waypoint construction path. Do not bypass it because it enforces the relationship between x/y/z and precise coordinates.
Inputs:
x/y/z are nominal block coordinates; name may be null; color is an RGB int; flags are bit flags; customRadius is a block radius override; tempMode/expiresAtMillis describe temporary waypoint expiry; preciseX/preciseY/preciseZ are absolute world coordinates in sixteenths of a block.
Outputs:
Creates an immutable waypoint whose name is non-null and whose x/y/z fields are the block containing the precise center.
Side effects:
None.
Failure modes:
None expected; extreme coordinate values can overflow only if external callers create impossible precise values far outside Minecraft's practical coordinate range.
Important invariants:
For all constructed waypoints, x == floorDiv(preciseX, PRECISE_SCALE), with the same rule for y and z.
Internal logic:
Replace a null name with an empty string, then derive x/y/z from the precise center fields.
Pseudocode:
if name is null, set it to empty string
x = blockCoordinateFromPrecise(preciseX)
y = blockCoordinateFromPrecise(preciseY)
z = blockCoordinateFromPrecise(preciseZ)
Implementation notes:
Legacy constructors supply default precise center values, so existing block waypoints behave exactly as before.
AI self-check:
Verify every constructor delegates through this path and no helper can leave block and precise coordinates inconsistent.
]]*/
    public Waypoint {
        name = name == null ? "" : name;
        x = blockCoordinateFromPrecise(preciseX);
        y = blockCoordinateFromPrecise(preciseY);
        z = blockCoordinateFromPrecise(preciseZ);
    }

    /**
     * Backward-compatible constructor for call sites that pre-date temp waypoints.
     * Anything built this way is treated as permanent (tempMode=0, expiresAt=0).
     */
    /*[[AI-FN-DOC
Function:
Waypoint block-coordinate compatibility constructor.
Purpose:
Create a permanent waypoint from integer block coordinates while defaulting its precise center to the middle of that block.
Why this exists:
Most imports, commands, and UI fields are block-oriented and should not need to know about small waypoint sixteenth-block precision.
When to use:
Use for normal permanent waypoints and legacy callers. Do not use when committing a precise small-waypoint reposition; use withPreciseSixteenths or the full constructor path instead.
Inputs:
x/y/z are integer block coordinates; name may be null; color is RGB; flags are waypoint flags; customRadius is a radius override where zero means group default.
Outputs:
Constructs a permanent waypoint with tempMode TEMP_NONE, no expiry, and precise center x/y/z + 0.5.
Side effects:
None.
Failure modes:
None expected for normal Minecraft coordinate ranges.
Important invariants:
The precise center produced here must match the old renderer's x + 0.5, y + 0.5, z + 0.5 behavior.
Internal logic:
Delegate to the temp-aware constructor with TEMP_NONE and no expiry.
Pseudocode:
call Waypoint(x, y, z, name, color, flags, customRadius, TEMP_NONE, 0)
Implementation notes:
Keeping this constructor avoids churn in public API and import code.
AI self-check:
Verify normal waypoint creation remains source-compatible.
]]*/
    public Waypoint(int x, int y, int z, String name, int color, int flags, double customRadius) {
        this(x, y, z, name, color, flags, customRadius, TEMP_NONE, 0L);
    }

    /*[[AI-FN-DOC
Function:
Waypoint temp-aware block-coordinate constructor.
Purpose:
Create a waypoint with explicit temporary metadata while keeping its precise center at the middle of its block.
Why this exists:
Temporary waypoint flows predate sub-block precision and still create block-centered markers from chat, commands, and API calls.
When to use:
Use when caller needs to set tempMode/expiresAtMillis at construction time from block coordinates. Do not use for precise small-marker movement.
Inputs:
x/y/z are block coordinates; name/color/flags/customRadius are waypoint visual and behavior data; tempMode is a TEMP_* constant; expiresAtMillis is the deadline for time-based temps.
Outputs:
Constructs a waypoint with precise center values derived from x/y/z.
Side effects:
None.
Failure modes:
None expected for normal coordinate ranges.
Important invariants:
Legacy block-created waypoints always render and measure from block center until explicitly given precise sixteenths.
Internal logic:
Delegate to the canonical record constructor with preciseBlockCenter for every axis.
Pseudocode:
px = preciseBlockCenter(x)
py = preciseBlockCenter(y)
pz = preciseBlockCenter(z)
call canonical constructor with supplied fields and px/py/pz
Implementation notes:
This is the single bridge from integer block coordinates into the precise-coordinate record shape.
AI self-check:
Verify all existing constructor call sites still route through this default precision behavior.
]]*/
    public Waypoint(int x, int y, int z, String name, int color, int flags,
                    double customRadius, int tempMode, long expiresAtMillis) {
        this(x, y, z, name, color, flags, customRadius, tempMode, expiresAtMillis,
                preciseBlockCenter(x), preciseBlockCenter(y), preciseBlockCenter(z));
    }

    /*[[AI-FN-DOC
Function:
at.
Purpose:
Create a plain unnamed waypoint at integer block coordinates.
Why this exists:
Tests, imports, and simple creation paths need a concise factory for the default waypoint shape.
When to use:
Use for ordinary permanent block-centered waypoints. Do not use when preserving an existing waypoint's visual flags or precise position.
Inputs:
x/y/z are integer block coordinates.
Outputs:
Returns a new default-colored waypoint centered in the supplied block.
Side effects:
None.
Failure modes:
None expected for normal coordinate ranges.
Important invariants:
The returned waypoint has no flags, no custom radius, no temp expiry, and default precise center values.
Internal logic:
Delegate to the compatibility constructor with empty name, DEFAULT_COLOR, zero flags, and zero radius.
Pseudocode:
return new Waypoint(x, y, z, "", DEFAULT_COLOR, 0, 0.0)
Implementation notes:
The constructor handles precise center initialization, keeping this factory intentionally small.
AI self-check:
Verify at still returns the same visible marker as before precision support.
]]*/
    public static Waypoint at(int x, int y, int z) {
        return new Waypoint(x, y, z, "", DEFAULT_COLOR, 0, 0.0);
    }

    public boolean hasName() {
        return !name.isEmpty();
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean isTemp() {
        return tempMode != TEMP_NONE;
    }

    public boolean isSubwaypoint() {
        return hasFlag(FLAG_SUBWAYPOINT);
    }

    /** True iff this is a time-based temp and the deadline has passed. */
    public boolean isExpired(long nowMillis) {
        return tempMode == TEMP_TIME && expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }

    /**
     * Invalid persisted/default temp modes fall back to REACH: no timer to
     * reason about, no server-scope tie-in, just "delete it after I go there."
     */
    public static int normalizeTempMode(int mode) {
        if (mode < TEMP_TIME || mode > TEMP_UNTIL_LEAVE) return TEMP_UNTIL_REACHED;
        return mode;
    }

    public static String tempModeName(int mode) {
        return switch (mode) {
            case TEMP_TIME          -> "TIME";
            case TEMP_UNTIL_REACHED -> "REACH";
            case TEMP_UNTIL_LEAVE   -> "LEAVE";
            default -> "?";
        };
    }

    /*[[AI-FN-DOC
Function:
withName.
Purpose:
Return a copy of this waypoint with a different label.
Why this exists:
Waypoints are immutable, so label edits need a copy helper that preserves every unrelated field including sub-block precision.
When to use:
Use from rename UI and import normalization when only the display name changes.
Inputs:
newName may be null, in which case the constructor normalizes it to an empty string.
Outputs:
Returns a new waypoint with the supplied name and all other fields unchanged.
Side effects:
None.
Failure modes:
None.
Important invariants:
Precise center fields must be preserved exactly.
Internal logic:
Construct a copy with newName and the current waypoint's remaining fields.
Pseudocode:
return new Waypoint(current x/y/z, newName, current color/flags/radius/temp/precision)
Implementation notes:
Passing precise values through prevents renaming a small marker from snapping back to block center.
AI self-check:
Verify only the name can change.
]]*/
    public Waypoint withName(String newName) {
        return new Waypoint(x, y, z, newName, color, flags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    /*[[AI-FN-DOC
Function:
withColor.
Purpose:
Return a copy of this waypoint with a different RGB color.
Why this exists:
Route color modes and swatches need immutable color edits that preserve position, flags, radius, and temp metadata.
When to use:
Use whenever changing only a waypoint's color.
Inputs:
newColor is an RGB integer; callers generally mask it before or after selection, but the value is stored as supplied.
Outputs:
Returns a new waypoint with the supplied color and all other fields unchanged.
Side effects:
None.
Failure modes:
None.
Important invariants:
Sub-block precision must survive color changes.
Internal logic:
Construct a copy with newColor and existing remaining fields.
Pseudocode:
return copy with color = newColor
Implementation notes:
No masking is added here to preserve existing behavior; group/config color setters already mask route-level colors.
AI self-check:
Verify only color changes.
]]*/
    public Waypoint withColor(int newColor) {
        return new Waypoint(x, y, z, name, newColor, flags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    /*[[AI-FN-DOC
Function:
withFlags.
Purpose:
Return a copy of this waypoint with a different flag bitset.
Why this exists:
Visual and structural toggles need one immutable copy path for changing flags while preserving the waypoint's location.
When to use:
Use for flag-only edits such as hide-name, hide-beacon, through-walls, locked color, subwaypoint, small, and filled states.
Inputs:
newFlags is the complete replacement bitset.
Outputs:
Returns a new waypoint with newFlags and all other fields unchanged.
Side effects:
None.
Failure modes:
None.
Important invariants:
Toggling small/filled/subwaypoint flags must not alter precise center values.
Internal logic:
Construct a copy with newFlags and existing remaining fields.
Pseudocode:
return copy with flags = newFlags
Implementation notes:
withSubwaypoint performs structural cleanup on top of this lower-level primitive.
AI self-check:
Verify only flags change.
]]*/
    public Waypoint withFlags(int newFlags) {
        return new Waypoint(x, y, z, name, color, newFlags, customRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    /*[[AI-FN-DOC
Function:
withRadius.
Purpose:
Return a copy of this waypoint with a different custom reach radius.
Why this exists:
Per-waypoint radius edits need to preserve route position and visual metadata while changing only the override value.
When to use:
Use when setting or clearing a waypoint-specific radius. Do not use for route default radius changes.
Inputs:
newRadius is the new radius override; zero means use the group's default.
Outputs:
Returns a new waypoint with the supplied radius and all other fields unchanged.
Side effects:
None.
Failure modes:
None.
Important invariants:
Small-waypoint precision must survive radius edits.
Internal logic:
Construct a copy with newRadius and existing remaining fields.
Pseudocode:
return copy with customRadius = newRadius
Implementation notes:
Validation remains in group effectiveRadius/default radius logic, matching previous behavior.
AI self-check:
Verify only radius changes.
]]*/
    public Waypoint withRadius(double newRadius) {
        return new Waypoint(x, y, z, name, color, flags, newRadius,
                tempMode, expiresAtMillis, preciseX, preciseY, preciseZ);
    }

    /*[[AI-FN-DOC
Function:
withPos.
Purpose:
Return a copy of this waypoint moved to integer block coordinates.
Why this exists:
Coordinate text boxes, normal repositioning, and legacy imports operate on whole blocks and should reset sub-block precision to the new block center.
When to use:
Use for block-level moves. Do not use for small waypoint precise repositioning; use withPreciseSixteenths instead.
Inputs:
nx/ny/nz are integer block coordinates.
Outputs:
Returns a new waypoint at the supplied block, centered at nx/ny/nz + 0.5.
Side effects:
None.
Failure modes:
None expected for normal coordinate ranges.
Important invariants:
Block-level moves intentionally clear previous precise offsets by deriving new precise center values from nx/ny/nz.
Internal logic:
Construct a waypoint through the temp-aware block constructor with existing non-position fields.
Pseudocode:
return new Waypoint(nx, ny, nz, name, color, flags, radius, tempMode, expiresAtMillis)
Implementation notes:
This preserves the old "move to block" semantics for every non-small workflow.
AI self-check:
Verify a precise small marker snaps to the new block center when edited through integer coordinate boxes.
]]*/
    public Waypoint withPos(int nx, int ny, int nz) {
        return new Waypoint(nx, ny, nz, name, color, flags, customRadius,
                tempMode, expiresAtMillis);
    }

    /*[[AI-FN-DOC
Function:
withPreciseSixteenths.
Purpose:
Return a copy of this waypoint with its center moved to absolute sixteenth-block coordinates.
Why this exists:
Small waypoint repositioning needs to place the tiny marker at 1/16 block precision without changing the legacy integer-coordinate API to doubles.
When to use:
Use for precise small waypoint movement after snapping a target location to sixteenths. Do not use for normal block-level moves.
Inputs:
nextPreciseX/nextPreciseY/nextPreciseZ are absolute world coordinates multiplied by PRECISE_SCALE.
Outputs:
Returns a new waypoint whose precise center is exactly the supplied sixteenths and whose x/y/z are normalized to the containing block.
Side effects:
None.
Failure modes:
None expected unless callers supply impossible extreme values.
Important invariants:
x/y/z must match floorDiv(precise axis, PRECISE_SCALE) after construction.
Internal logic:
Construct a copy with supplied precise fields and existing visual/temp fields.
Pseudocode:
return new Waypoint(existing block coords, same metadata, supplied precise fields)
Implementation notes:
The canonical constructor derives block coords from the precise values, so the x/y/z arguments are placeholders for record construction compatibility.
AI self-check:
Verify centerX/centerY/centerZ reflect the supplied sixteenths exactly divided by PRECISE_SCALE.
]]*/
    public Waypoint withPreciseSixteenths(int nextPreciseX, int nextPreciseY, int nextPreciseZ) {
        return new Waypoint(x, y, z, name, color, flags, customRadius,
                tempMode, expiresAtMillis, nextPreciseX, nextPreciseY, nextPreciseZ);
    }

    /*[[AI-FN-DOC
Function:
centerX.
Purpose:
Return the waypoint's world-space center on the X axis.
Why this exists:
Rendering, labels, beams, connectors, and proximity checks need one source of truth that honors small waypoint sub-block precision.
When to use:
Use whenever code needs the waypoint's center rather than the integer block minimum.
Inputs:
None.
Outputs:
Returns preciseX divided by PRECISE_SCALE as a double.
Side effects:
None.
Failure modes:
None.
Important invariants:
Legacy block-centered waypoints return x + 0.5 because their preciseX defaults to x * PRECISE_SCALE + 5.
Internal logic:
Divide preciseX by PRECISE_SCALE.
Pseudocode:
return preciseX / 10.0
Implementation notes:
This method is intentionally axis-specific so call sites stay clear without allocating vector objects.
AI self-check:
Verify negative coordinates produce correct decimal centers.
]]*/
    public double centerX() {
        return preciseX / (double) PRECISE_SCALE;
    }

    /*[[AI-FN-DOC
Function:
centerY.
Purpose:
Return the waypoint's world-space center on the Y axis.
Why this exists:
Sub-block small waypoints need vertical precision too, and all center-based math should read the same stored precision model.
When to use:
Use whenever code needs the waypoint's center height rather than the block minimum.
Inputs:
None.
Outputs:
Returns preciseY divided by PRECISE_SCALE as a double.
Side effects:
None.
Failure modes:
None.
Important invariants:
Legacy block-centered waypoints return y + 0.5.
Internal logic:
Divide preciseY by PRECISE_SCALE.
Pseudocode:
return preciseY / 10.0
Implementation notes:
Keeping Y precise allows small markers to sit on shelves, faces, or partial-block details.
AI self-check:
Verify this matches centerX and centerZ semantics.
]]*/
    public double centerY() {
        return preciseY / (double) PRECISE_SCALE;
    }

    /*[[AI-FN-DOC
Function:
centerZ.
Purpose:
Return the waypoint's world-space center on the Z axis.
Why this exists:
Rendering and proximity need the same sub-block center for the third axis.
When to use:
Use whenever code needs the waypoint's world-space center rather than the block minimum.
Inputs:
None.
Outputs:
Returns preciseZ divided by PRECISE_SCALE as a double.
Side effects:
None.
Failure modes:
None.
Important invariants:
Legacy block-centered waypoints return z + 0.5.
Internal logic:
Divide preciseZ by PRECISE_SCALE.
Pseudocode:
return preciseZ / 10.0
Implementation notes:
This keeps connector line endpoints and label anchors aligned with the rendered small cube.
AI self-check:
Verify this matches centerX and centerY semantics.
]]*/
    public double centerZ() {
        return preciseZ / (double) PRECISE_SCALE;
    }

    /*[[AI-FN-DOC
Function:
hasCustomPrecisePosition.
Purpose:
Report whether this waypoint's stored precise center differs from the default center of its block.
Why this exists:
Local storage should omit precise fields for normal block-centered waypoints so existing JSON stays tidy and backward-friendly.
When to use:
Use before serializing optional preciseX/preciseY/preciseZ fields. Do not use to decide whether the renderer can call centerX/Y/Z; those are always valid.
Inputs:
None.
Outputs:
Returns true when at least one precise axis is not the default block center.
Side effects:
None.
Failure modes:
None.
Important invariants:
For any waypoint built through block-coordinate constructors or withPos, this returns false.
Internal logic:
Compare each precise axis against preciseBlockCenter for its corresponding block coordinate.
Pseudocode:
return preciseX != preciseBlockCenter(x) or preciseY != preciseBlockCenter(y) or preciseZ != preciseBlockCenter(z)
Implementation notes:
The method is public because storage lives in a different package.
AI self-check:
Verify a marker at x+0.5/y+0.5/z+0.5 does not serialize optional precision fields.
]]*/
    public boolean hasCustomPrecisePosition() {
        return preciseX != preciseBlockCenter(x)
                || preciseY != preciseBlockCenter(y)
                || preciseZ != preciseBlockCenter(z);
    }

    /*[[AI-FN-DOC
Function:
snapToPreciseSixteenths.
Purpose:
Convert a world coordinate to the nearest stored sixteenth-block integer.
Why this exists:
Small waypoint repositioning needs deterministic snapping from a BlockHitResult hit location into the persisted precise coordinate model.
When to use:
Use at input boundaries where a double world coordinate should become waypoint precision. Do not use for display-only formatting.
Inputs:
coordinate is a finite or non-finite double world coordinate.
Outputs:
Returns Math.round(coordinate * PRECISE_SCALE) cast to int.
Side effects:
None.
Failure modes:
Non-finite or extremely large coordinates follow Java Math.round/cast behavior; live Minecraft hit locations are expected to be finite and practical.
Important invariants:
Every integer step in the result corresponds to exactly one sixteenth of a block.
Internal logic:
Multiply by PRECISE_SCALE and round to nearest long, then cast to int for storage.
Pseudocode:
return (int) Math.round(coordinate * PRECISE_SCALE)
Implementation notes:
Rounding rather than flooring makes the tiny preview land on the nearest visible cursor hit, which feels better for manual placement.
AI self-check:
Verify negative coordinates round symmetrically enough for normal Minecraft placement.
]]*/
    public static int snapToPreciseSixteenths(double coordinate) {
        return (int) Math.round(coordinate * PRECISE_SCALE);
    }

    /*[[AI-FN-DOC
Function:
withSubwaypoint
Purpose:
Return a copy of this waypoint with its structural subwaypoint flag enabled or disabled.
Why this exists:
Waypoint records are immutable, and callers need one safe helper for changing parent/child route structure without manually editing flag bits.
When to use:
Use whenever a waypoint is promoted to or demoted from subwaypoint status. Do not use for unrelated visual flag changes.
Inputs:
subwaypoint is true to mark this waypoint as a child of the nearest previous main waypoint, false to promote it back to a main waypoint.
Outputs:
Returns a new Waypoint with updated flags and all other record fields preserved.
Side effects:
None.
Failure modes:
None. Structural validity, such as preventing index 0 from becoming a subwaypoint, is enforced by WaypointGroup.
Important invariants:
Subwaypoint-only visual flags are cleared when the waypoint is promoted back to a main waypoint so small/filled styling cannot linger invisibly.
Internal logic:
If enabling, OR in FLAG_SUBWAYPOINT. If disabling, clear FLAG_SUBWAYPOINT and every SUBWAYPOINT_STYLE_FLAGS bit.
Pseudocode:
if subwaypoint:
  nextFlags = flags OR FLAG_SUBWAYPOINT
else:
  nextFlags = flags AND NOT FLAG_SUBWAYPOINT AND NOT SUBWAYPOINT_STYLE_FLAGS
return withFlags(nextFlags)
Implementation notes:
Keeping the cleanup here protects all callers, including GUI toggles, storage normalization, and future commands.
AI self-check:
Verify demoting a styled subwaypoint removes small and filled flags.
]]*/
    public Waypoint withSubwaypoint(boolean subwaypoint) {
        int nextFlags = subwaypoint
                ? flags | FLAG_SUBWAYPOINT
                : flags & ~FLAG_SUBWAYPOINT & ~SUBWAYPOINT_STYLE_FLAGS;
        return withFlags(nextFlags);
    }

    /** Flip a waypoint's temp mode. Typically used to build a brand-new temp waypoint from {@link #at}. */
    /*[[AI-FN-DOC
Function:
withTemp.
Purpose:
Return a copy of this waypoint with new temporary waypoint metadata.
Why this exists:
Temp waypoint flows need to mark an otherwise normal waypoint as expiring without rebuilding or losing its visual/position metadata.
When to use:
Use when creating or updating temporary waypoints. Do not use for normal persistent waypoint edits.
Inputs:
mode is one of the TEMP_* constants; expiresAt is the epoch-millis deadline for time-based temps or zero for non-time modes.
Outputs:
Returns a new waypoint with updated tempMode and expiresAtMillis and every other field preserved.
Side effects:
None.
Failure modes:
Invalid temp modes are not normalized here; callers that accept external mode values should use normalizeTempMode first.
Important invariants:
Sub-block precision must survive temporary-mode changes.
Internal logic:
Construct a copy with supplied temp metadata and existing position/visual fields.
Pseudocode:
return copy with tempMode = mode and expiresAtMillis = expiresAt
Implementation notes:
Storage still skips temp waypoints regardless of their precise fields.
AI self-check:
Verify no position or flag fields are changed.
]]*/
    public Waypoint withTemp(int mode, long expiresAt) {
        return new Waypoint(x, y, z, name, color, flags, customRadius, mode, expiresAt,
                preciseX, preciseY, preciseZ);
    }

    /*[[AI-FN-DOC
Function:
preciseBlockCenter.
Purpose:
Convert an integer block coordinate into the default sixteenth-block center coordinate.
Why this exists:
Every legacy block-created waypoint should keep the old center-at-half-block behavior while sharing the new integer precision representation.
When to use:
Use inside Waypoint constructors and precision-default checks.
Inputs:
blockCoordinate is an integer block coordinate.
Outputs:
Returns blockCoordinate multiplied by PRECISE_SCALE plus PRECISE_BLOCK_CENTER_OFFSET.
Side effects:
None.
Failure modes:
Very large coordinates could overflow int multiplication, but Minecraft's practical coordinate range is safely below that.
Important invariants:
The returned value divided by PRECISE_SCALE equals blockCoordinate + 0.5.
Internal logic:
Multiply by ten and add five.
Pseudocode:
return blockCoordinate * 10 + 5
Implementation notes:
The helper avoids repeating a magic offset wherever default precision is needed.
AI self-check:
Verify negative coordinates still produce the expected center, e.g. -1 maps to -0.5.
]]*/
    private static int preciseBlockCenter(int blockCoordinate) {
        return blockCoordinate * PRECISE_SCALE + PRECISE_BLOCK_CENTER_OFFSET;
    }

    /*[[AI-FN-DOC
Function:
blockCoordinateFromPrecise.
Purpose:
Derive the containing block coordinate from an absolute sixteenth-block center coordinate.
Why this exists:
The public x/y/z fields need to remain consistent with the precise center so legacy code can still bucket and display the containing block.
When to use:
Use from the canonical constructor when normalizing x/y/z from precise fields.
Inputs:
preciseCoordinate is an absolute coordinate multiplied by PRECISE_SCALE.
Outputs:
Returns the floor block coordinate containing that precise coordinate.
Side effects:
None.
Failure modes:
None.
Important invariants:
Negative coordinates must floor toward negative infinity, not truncate toward zero.
Internal logic:
Use Math.floorDiv by PRECISE_SCALE.
Pseudocode:
return floorDiv(preciseCoordinate, 10)
Implementation notes:
floorDiv is required so a center at -1/16 belongs to block -1 rather than block 0.
AI self-check:
Verify positive and negative precise coordinates map to the correct containing block.
]]*/
    private static int blockCoordinateFromPrecise(int preciseCoordinate) {
        return Math.floorDiv(preciseCoordinate, PRECISE_SCALE);
    }
}
