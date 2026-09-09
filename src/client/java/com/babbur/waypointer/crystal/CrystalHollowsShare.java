package com.babbur.waypointer.crystal;

import com.babbur.waypointer.compat.MinecraftCompat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;

/** Chat share buttons send the captured sighting; route actions prepare editable text. */
public final class CrystalHollowsShare {
    private CrystalHollowsShare() {}

    public static ClickEvent.RunCommand share(StructureSighting sighting) {
        return new ClickEvent.RunCommand("/wpch share " + sighting.structure().id()
                + " " + sighting.x() + " " + sighting.y() + " " + sighting.z());
    }

    private static String message(StructureSighting sighting) {
        return CrystalHollowsChatParser.formatShare(
                sighting.structure(), sighting.x(), sighting.y(), sighting.z());
    }

    public static void openDraft(CrystalHollowsTracker tracker, String reference) {
        if (tracker == null || !tracker.active() || tracker.lobby() == null) return;
        StructureSighting sighting = resolve(tracker.lobby().sightings(), reference,
                tracker.compassTargetSighting());
        if (sighting == null) return;
        MinecraftCompat.setScreen(Minecraft.getInstance(), new ChatScreen(message(sighting), false));
    }

    static StructureSighting resolve(List<StructureSighting> sightings, String reference,
                                     StructureSighting compassTarget) {
        var selection = CrystalHollowsSightingSelector.parse(reference);
        if (selection == null) return null;
        StructureSighting sighting = CrystalHollowsSightingSelector.find(sightings, selection);
        if (sighting != null) return sighting;
        if (selection.structure() == CrystalHollowsStructure.WISHING_TARGET && selection.instance() == 1)
            return compassTarget;
        if (selection.structure() == CrystalHollowsStructure.CRYSTAL_NUCLEUS) {
            return new StructureSighting(CrystalHollowsStructure.CRYSTAL_NUCLEUS,
                    (int) Math.floor(CrystalHollowsGeometry.NUCLEUS_CENTRE_X),
                    (int) Math.floor(CrystalHollowsGeometry.NUCLEUS_CENTRE_Y),
                    (int) Math.floor(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z),
                    SightingConfidence.MANUAL, "fixed:nucleus", 0L);
        }
        return null;
    }
}
