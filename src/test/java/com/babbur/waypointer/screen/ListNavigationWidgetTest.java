package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ListNavigationWidgetTest {

    @Test
    void keyboardMovementClampsToTheAvailableRows() {
        assertEquals(-1, ListNavigationWidget.moveIndex(0, 0, 1));
        assertEquals(0, ListNavigationWidget.moveIndex(-1, 4, -1));
        assertEquals(0, ListNavigationWidget.moveIndex(-1, 4, 1));
        assertEquals(0, ListNavigationWidget.moveIndex(0, 4, -10));
        assertEquals(3, ListNavigationWidget.moveIndex(2, 4, 10));
    }

    @Test
    void keyboardLayerIsPointerTransparentForThePaintedRowsBelowIt() {
        ListNavigationWidget widget = new ListNavigationWidget(
                10, 20, 100, 80, 0, 22, 20,
                () -> 3, () -> 0, ignored -> net.minecraft.network.chat.Component.empty(),
                ignored -> {}, () -> 0, ignored -> {});

        assertFalse(widget.isMouseOver(50, 50));
    }
}
