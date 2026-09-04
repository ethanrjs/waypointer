package com.babbur.waypointer.crystal;

import java.util.Objects;

/** Classifies a newly resolved server id without depending on Minecraft client state. */
public final class CrystalHollowsLobbyTransition {

    public enum Kind {
        SAME,
        SESSION_IDENTIFIED,
        DIFFERENT_LOBBY
    }

    private CrystalHollowsLobbyTransition() {}

    public static Kind classify(String currentServerId, String resolvedServerId) {
        Objects.requireNonNull(resolvedServerId, "resolvedServerId");
        if (resolvedServerId.equals(currentServerId)) return Kind.SAME;
        return currentServerId == null ? Kind.SESSION_IDENTIFIED : Kind.DIFFERENT_LOBBY;
    }
}
