package com.babbur.waypointer.crystal;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Stable, instance-aware references for lobby sightings and runtime structure groups. */
public final class CrystalHollowsSightingSelector {

    public record Selection(CrystalHollowsStructure structure, int instance) {
        public Selection {
            Objects.requireNonNull(structure, "structure");
            if (instance < 1 || !structure.multiInstance() && instance != 1) {
                throw new IllegalArgumentException("invalid structure instance");
            }
        }

        public String reference() {
            return structure.id() + (instance == 1 ? "" : ":" + instance);
        }
    }

    private CrystalHollowsSightingSelector() {}

    public static Selection parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String structureText = raw.trim();
        int instance = 1;
        int separator = structureText.lastIndexOf(':');
        if (separator >= 0 && separator + 1 < structureText.length()) {
            try {
                instance = Integer.parseInt(structureText.substring(separator + 1));
                structureText = structureText.substring(0, separator);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        CrystalHollowsStructure structure = resolveStructure(structureText);
        if (structure == null || instance < 1 || !structure.multiInstance() && instance != 1) {
            return null;
        }
        return new Selection(structure, instance);
    }

    public static StructureSighting find(
            List<StructureSighting> sightings, Selection selection) {
        int index = indexOf(sightings, selection);
        return index < 0 ? null : sightings.get(index);
    }

    public static int indexOf(List<StructureSighting> sightings, Selection selection) {
        if (sightings == null || selection == null) return -1;
        int occurrence = 0;
        for (int index = 0; index < sightings.size(); index++) {
            StructureSighting sighting = sightings.get(index);
            if (sighting.structure() != selection.structure()) continue;
            occurrence++;
            if (occurrence == selection.instance()) return index;
        }
        return -1;
    }

    public static String referenceFor(
            List<StructureSighting> sightings, StructureSighting target) {
        if (sightings == null || target == null) return null;
        int equalIndex = -1;
        for (int index = 0; index < sightings.size(); index++) {
            StructureSighting sighting = sightings.get(index);
            if (sighting == target) return referenceAt(sightings, index);
            if (equalIndex < 0 && sighting.equals(target)) equalIndex = index;
        }
        return equalIndex < 0 ? null : referenceAt(sightings, equalIndex);
    }

    public static String referenceAt(List<StructureSighting> sightings, int index) {
        if (sightings == null || index < 0 || index >= sightings.size()) return null;
        CrystalHollowsStructure structure = sightings.get(index).structure();
        int occurrence = 0;
        for (int current = 0; current <= index; current++) {
            if (sightings.get(current).structure() == structure) occurrence++;
        }
        return new Selection(structure, occurrence).reference();
    }

    public static CrystalHollowsStructure resolveStructure(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CrystalHollowsStructure structure : CrystalHollowsStructure.values()) {
            if (structure.id().equals(normalized)) return structure;
        }
        return CrystalHollowsChatParser.structureFromText(raw);
    }
}
