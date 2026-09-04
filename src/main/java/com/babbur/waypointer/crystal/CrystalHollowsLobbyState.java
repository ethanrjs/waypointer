package com.babbur.waypointer.crystal;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Mutable, client-thread-owned state for one Crystal Hollows server instance. */
public final class CrystalHollowsLobbyState {

    public enum MergeResult {
        ADDED,
        UPGRADED,
        REFINED,
        IGNORED
    }

    private static final double MULTI_INSTANCE_DISTANCE_SQUARED = 60.0 * 60.0;
    private static final double EQUAL_REFINEMENT_DISTANCE_SQUARED = 2.0 * 2.0;
    private static final double COMPASS_CONFIRM_DISTANCE_SQUARED = 80.0 * 80.0;

    private final String serverId;
    private final long firstSeenMillis;
    private long lastSeenMillis;
    private int lastKnownDay;
    private final List<StructureSighting> sightings;
    private final EnumMap<Crystal, CrystalState> crystals;
    private CrystalHollowsPosition divanCentre;

    public CrystalHollowsLobbyState(String serverId, long nowMillis, int currentDay) {
        this(serverId, nowMillis, nowMillis, currentDay, List.of(), Map.of(), null);
    }

    CrystalHollowsLobbyState(String serverId, long firstSeenMillis, long lastSeenMillis,
                             int lastKnownDay, List<StructureSighting> sightings,
                             Map<Crystal, CrystalState> crystals,
                             CrystalHollowsPosition divanCentre) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.firstSeenMillis = firstSeenMillis;
        this.lastSeenMillis = lastSeenMillis;
        this.lastKnownDay = lastKnownDay;
        this.sightings = new ArrayList<>(sightings);
        this.crystals = new EnumMap<>(Crystal.class);
        this.crystals.putAll(crystals);
        this.divanCentre = divanCentre;
    }

    public String serverId() { return serverId; }
    public long firstSeenMillis() { return firstSeenMillis; }
    public long lastSeenMillis() { return lastSeenMillis; }
    public int lastKnownDay() { return lastKnownDay; }
    public List<StructureSighting> sightings() { return Collections.unmodifiableList(sightings); }
    public Map<Crystal, CrystalState> crystals() { return Collections.unmodifiableMap(crystals); }
    public CrystalHollowsPosition divanCentre() { return divanCentre; }

    public void touch(long nowMillis, int currentDay) {
        lastSeenMillis = Math.max(lastSeenMillis, nowMillis);
        if (currentDay >= 0) lastKnownDay = currentDay;
    }

    public void setCrystal(Crystal crystal, CrystalState state) {
        crystals.put(Objects.requireNonNull(crystal, "crystal"),
                Objects.requireNonNull(state, "state"));
    }

    public void resetCrystals() {
        crystals.clear();
        for (Crystal crystal : Crystal.values()) crystals.put(crystal, CrystalState.MISSING);
    }

    public void replaceCrystals(Map<Crystal, CrystalState> states) {
        crystals.clear();
        crystals.putAll(states);
    }

    public void setDivanCentre(CrystalHollowsPosition position) {
        divanCentre = position;
    }

    public MergeResult merge(StructureSighting incoming) {
        Objects.requireNonNull(incoming, "incoming");
        lastSeenMillis = Math.max(lastSeenMillis, incoming.atMillis());
        if (incoming.structure() == CrystalHollowsStructure.WISHING_TARGET) {
            sightings.add(incoming);
            return MergeResult.ADDED;
        }

        int existingIndex = findInstance(incoming);
        MergeResult result;
        if (existingIndex < 0) {
            sightings.add(incoming);
            result = MergeResult.ADDED;
        } else {
            StructureSighting existing = sightings.get(existingIndex);
            result = mergeAt(existingIndex, existing, incoming);
        }
        boolean removedAmbiguous = removeMatchingWishingTargets(incoming);
        return result == MergeResult.IGNORED && removedAmbiguous ? MergeResult.REFINED : result;
    }

    public boolean removeStructure(CrystalHollowsStructure structure) {
        return sightings.removeIf(sighting -> sighting.structure() == structure);
    }

    public boolean removeSighting(CrystalHollowsSightingSelector.Selection selection) {
        int index = CrystalHollowsSightingSelector.indexOf(sightings, selection);
        if (index < 0) return false;
        sightings.remove(index);
        return true;
    }

    /** Reconciles observations collected before the Hypixel server id became available. */
    public static CrystalHollowsLobbyState identify(
            String serverId,
            int currentDay,
            long nowMillis,
            CrystalHollowsLobbyState restored,
            CrystalHollowsLobbyState session) {
        Objects.requireNonNull(serverId, "serverId");
        long firstSeen = nowMillis;
        long lastSeen = nowMillis;
        int knownDay = currentDay;
        List<StructureSighting> restoredSightings = List.of();
        Map<Crystal, CrystalState> restoredCrystals = Map.of();
        CrystalHollowsPosition restoredDivan = null;
        if (restored != null) {
            if (!serverId.equals(restored.serverId())) {
                throw new IllegalArgumentException("restored lobby id differs");
            }
            firstSeen = Math.min(firstSeen, restored.firstSeenMillis());
            lastSeen = Math.max(lastSeen, restored.lastSeenMillis());
            if (knownDay < 0) knownDay = restored.lastKnownDay();
            restoredSightings = restored.sightings();
            restoredCrystals = restored.crystals();
            restoredDivan = restored.divanCentre();
        }
        if (session != null) {
            firstSeen = Math.min(firstSeen, session.firstSeenMillis());
            lastSeen = Math.max(lastSeen, session.lastSeenMillis());
            if (knownDay < 0) knownDay = session.lastKnownDay();
        }
        CrystalHollowsLobbyState identified = new CrystalHollowsLobbyState(
                serverId, firstSeen, lastSeen, knownDay,
                restoredSightings, restoredCrystals, restoredDivan);
        if (session != null) {
            for (StructureSighting sighting : session.sightings()) identified.merge(sighting);
            identified.crystals.putAll(session.crystals());
            if (session.divanCentre() != null) identified.divanCentre = session.divanCentre();
        }
        identified.touch(nowMillis, currentDay);
        return identified;
    }

    public void clearSightings() {
        sightings.clear();
        divanCentre = null;
    }

    private MergeResult mergeAt(int index, StructureSighting existing, StructureSighting incoming) {
        if (existing.confidence() == SightingConfidence.MANUAL
                && incoming.confidence() != SightingConfidence.MANUAL) {
            return MergeResult.IGNORED;
        }
        int confidence = Integer.compare(incoming.confidence().ordinal(),
                existing.confidence().ordinal());
        if (confidence > 0) {
            sightings.set(index, incoming);
            return MergeResult.UPGRADED;
        }
        if (confidence == 0) {
            if (incoming.confidence() == SightingConfidence.ROUGH_AREA) {
                if (samePosition(existing, incoming) && incoming.atMillis() <= existing.atMillis()) {
                    return MergeResult.IGNORED;
                }
                sightings.set(index, incoming);
                return MergeResult.REFINED;
            }
            if (existing.position().distanceSquared(incoming.position())
                    > EQUAL_REFINEMENT_DISTANCE_SQUARED
                    && incoming.atMillis() > existing.atMillis()) {
                sightings.set(index, incoming);
                return MergeResult.REFINED;
            }
            return MergeResult.IGNORED;
        }
        if (incoming.confidence() == SightingConfidence.COMPASS
                && existing.confidence() == SightingConfidence.ENTITY
                && existing.position().distanceSquared(incoming.position())
                        <= COMPASS_CONFIRM_DISTANCE_SQUARED
                && !"confirmed by compass".equals(existing.note())) {
            sightings.set(index, existing.withNote("confirmed by compass"));
            return MergeResult.REFINED;
        }
        return MergeResult.IGNORED;
    }

    private int findInstance(StructureSighting incoming) {
        for (int index = 0; index < sightings.size(); index++) {
            StructureSighting existing = sightings.get(index);
            if (existing.structure() != incoming.structure()) continue;
            if (!incoming.structure().multiInstance()
                    || existing.position().distanceSquared(incoming.position())
                            <= MULTI_INSTANCE_DISTANCE_SQUARED) {
                return index;
            }
        }
        return -1;
    }

    private boolean removeMatchingWishingTargets(StructureSighting incoming) {
        return sightings.removeIf(existing -> existing.structure() == CrystalHollowsStructure.WISHING_TARGET
                && existing.candidates().contains(incoming.structure())
                && existing.position().distanceSquared(incoming.position())
                        <= COMPASS_CONFIRM_DISTANCE_SQUARED);
    }

    private static boolean samePosition(StructureSighting first, StructureSighting second) {
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z();
    }
}
