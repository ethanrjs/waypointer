package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** A screen that borrows the main route list to pick routes, then takes the screen back. */
interface RoutePickerHost {
    /** The screen to return to when picking ends. */
    Screen screen();

    /** Whether this route may join the selection (eligibility and kind compatibility). */
    boolean accepts(WaypointGroup group);

    boolean contains(WaypointGroup group);

    /** The route kind the selection is locked to, or {@code null} while nothing is chosen. */
    WaypointGroup.RouteKind routeKind();

    int routeCount();

    void addRoutes(List<WaypointGroup> routes);

    void removeRoutes(List<WaypointGroup> routes);
}
