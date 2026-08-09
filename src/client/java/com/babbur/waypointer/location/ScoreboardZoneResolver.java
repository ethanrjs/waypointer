package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects SkyBlock zones from the sidebar every two ticks. */
public final class ScoreboardZoneResolver implements ZoneSource {

    /** Captures the location name before an optional coordinate suffix. */
    private static final Pattern LOCATION_LINE = Pattern.compile(
            "(?m)⏣[\\t ]*([^,\\r\\n]+?)[\\t ]*(?:,|$)");

    private static final int POLL_INTERVAL_TICKS = 2;

    private Consumer<Zone> listener;
    private Zone lastEmitted;
    private int tickCounter = 0;

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        Zone z = detect(mc);
        if (!Objects.equals(z, lastEmitted)) {
            lastEmitted = z;
            Waypointer.LOGGER.debug("Scoreboard zone resolved to {}", z);
            if (listener != null) listener.accept(z);
        }
    }

    private Zone detect(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return null;
        return resolveSidebarText(SidebarTexts.collectColorStripped(mc));
    }

    static Zone resolveSidebarText(String blob) {
        if (blob == null || blob.isBlank()) return null;
        Matcher location = LOCATION_LINE.matcher(blob);
        if (!location.find()) return null;


        Zone mineshaftType = Zone.tryResolveMineshaftTypeFromSidebarBlob(blob);
        if (mineshaftType != null) return mineshaftType;
        Zone mineshaftArea = Zone.tryResolveDwarvenSubAreaFromSidebarBlob(blob);
        if (mineshaftArea != null) return mineshaftArea;
        Zone catacombsFloor = CatacombsFloorRefiner.tryResolveFromSidebarBlob(blob);
        if (catacombsFloor != null) return catacombsFloor;

        return Zone.resolveFromDisplayName(location.group(1).trim());
    }
}
