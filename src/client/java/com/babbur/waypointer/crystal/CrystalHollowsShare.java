package com.babbur.waypointer.crystal;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

public final class CrystalHollowsShare {
    private CrystalHollowsShare() {}

    public static ClickEvent.RunCommand share(StructureSighting sighting) {
        return new ClickEvent.RunCommand("/wpch sendcoords " + sighting.structure().id()
                + " " + sighting.x() + " " + sighting.y() + " " + sighting.z());
    }

    public static Component defocus() {
        return Component.literal(" ").append(
                Component.translatableWithFallback(
                        "waypointer.crystal.action.defocus", "[Defocus]")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/wpch defocus"))));
    }

    public static int send(CrystalHollowsStructure structure, int x, int y, int z) {
        if (structure == null || !CrystalHollowsGeometry.insideHollows(x, y, z)) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getConnection() == null) return 0;
        client.getConnection().sendChat(CrystalHollowsChatParser.formatShare(structure, x, y, z));
        return 1;
    }

    public static void send(CrystalHollowsTracker tracker, String reference) {
        if (tracker == null || !tracker.active() || tracker.lobby() == null) return;
        StructureSighting sighting = resolve(tracker.lobby().sightings(), reference,
                tracker.compassTargetSighting());
        if (sighting == null) return;
        send(sighting.structure(), sighting.x(), sighting.y(), sighting.z());
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
