package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrystalHollowsLobbyTransitionTest {

    @Test
    void distinguishesBootstrapFromARealLobbyBoundary() {
        assertEquals(CrystalHollowsLobbyTransition.Kind.SESSION_IDENTIFIED,
                CrystalHollowsLobbyTransition.classify(null, "m1A"));
        assertEquals(CrystalHollowsLobbyTransition.Kind.SAME,
                CrystalHollowsLobbyTransition.classify("m1A", "m1A"));
        assertEquals(CrystalHollowsLobbyTransition.Kind.DIFFERENT_LOBBY,
                CrystalHollowsLobbyTransition.classify("m1A", "m2B"));
    }
}
