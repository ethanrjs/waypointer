package dev.ethan.waypointer.diana;

/**
 * Stable ID for the client-managed Diana burrow overlay group. Declared in the
 * main module so {@link dev.ethan.waypointer.core.ActiveGroupManager} can skip
 * reach-based temp cleanup without depending on client-only detector code.
 */
public final class DianaBurrowWaypointGroup {

    public static final String ID = "diana::burrows";

    private DianaBurrowWaypointGroup() {}
}
