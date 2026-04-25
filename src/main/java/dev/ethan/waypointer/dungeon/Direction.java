package dev.ethan.waypointer.dungeon;

/**
 * Which corner of a room's physical bounding box is the canonical "northwest"
 * origin for that room's secret data.
 *
 * <p>Hypixel rotates rooms freely within their map cells, so a room's curated
 * waypoint coordinates are anchored to whichever physical corner the room
 * data was authored from. The direction-aware coordinate transforms in
 * {@link DungeonMapMath#relativeToActual} fan out to four cases keyed off
 * this enum.
 *
 * <p>Re-implemented from the algorithm in Skyblocker's
 * {@code de.hysky.skyblocker.skyblock.dungeon.secrets.Room.Direction}
 * (LGPL-3.0). The math is a literal copy of the rotation cases; no Skyblocker
 * code is included verbatim.
 */
public enum Direction { NW, NE, SW, SE }
