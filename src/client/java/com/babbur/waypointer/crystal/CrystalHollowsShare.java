package com.babbur.waypointer.crystal;

import com.babbur.waypointer.compat.MinecraftCompat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;

/** Share actions prepare editable chat text. Only the user's Send action transmits it. */
public final class CrystalHollowsShare {
    private CrystalHollowsShare() {}

    public static ClickEvent.SuggestCommand suggest(StructureSighting sighting) {
        return new ClickEvent.SuggestCommand(message(sighting));
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
