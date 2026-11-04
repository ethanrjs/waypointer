package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

interface RoutePickerHost {
    Screen screen();

    boolean accepts(WaypointGroup group);

    boolean contains(WaypointGroup group);

    WaypointGroup.RouteKind routeKind();

    int routeCount();

    void addRoutes(List<WaypointGroup> routes);

    void removeRoutes(List<WaypointGroup> routes);
}
