package com.babbur.waypointer.crystal;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
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
    // Keep local evidence while a more precise relay location is displayed.
    private final Map<StructureSighting, StructureSighting> localFallbacks = new IdentityHashMap<>();
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
    List<StructureSighting> localSightings() {
        List<StructureSighting> local = new ArrayList<>();
        for (StructureSighting sighting : sightings) {
            StructureSighting candidate = localFallbacks.getOrDefault(sighting, sighting);
            if (candidate.confidence() != SightingConfidence.SHARED_REMOTE) local.add(candidate);
        }
        return local;
    }

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
            StructureSighting previousFallback = localFallbacks.remove(existing);
            StructureSighting fallback = previousFallback;
            if (existing.confidence() == SightingConfidence.SHARED_REMOTE
                    && incoming.confidence() != SightingConfidence.SHARED_REMOTE) {
                if (fallback == null) fallback = incoming;
                else {
                    List<StructureSighting> local = new ArrayList<>(List.of(fallback));
                    mergeAt(local, 0, fallback, incoming);
                    fallback = local.getFirst();
                }
            } else if (incoming.confidence() == SightingConfidence.SHARED_REMOTE
                    && existing.confidence() != SightingConfidence.SHARED_REMOTE) {
                fallback = existing;
            }
            result = mergeAt(sightings, existingIndex, existing, incoming);
            StructureSighting displayed = sightings.get(existingIndex);
            if (displayed.confidence() == SightingConfidence.SHARED_REMOTE && fallback != null) {
                localFallbacks.put(displayed, fallback);
                if (incoming.confidence() != SightingConfidence.SHARED_REMOTE
                        && !Objects.equals(previousFallback, fallback)
                        && result == MergeResult.IGNORED) result = MergeResult.REFINED;
            }
        }
        boolean removedAmbiguous = incoming.confidence() != SightingConfidence.SHARED_REMOTE
                && removeMatchingWishingTargets(incoming);
        return result == MergeResult.IGNORED && removedAmbiguous ? MergeResult.REFINED : result;
    }

    public boolean removeStructure(CrystalHollowsStructure structure) {
        localFallbacks.keySet().removeIf(sighting -> sighting.structure() == structure);
        return sightings.removeIf(sighting -> sighting.structure() == structure);
    }

    public boolean removeSighting(CrystalHollowsSightingSelector.Selection selection) {
        int index = CrystalHollowsSightingSelector.indexOf(sightings, selection);
        if (index < 0) return false;
        localFallbacks.remove(sightings.remove(index));
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
        if (restored != null) identified.localFallbacks.putAll(restored.localFallbacks);
        if (session != null) {
            for (StructureSighting sighting : session.localSightings()) identified.merge(sighting);
            for (StructureSighting sighting : session.sightings()) {
                if (sighting.confidence() == SightingConfidence.SHARED_REMOTE) identified.merge(sighting);
            }
            identified.crystals.putAll(session.crystals());
            if (session.divanCentre() != null) identified.divanCentre = session.divanCentre();
        }
        identified.touch(nowMillis, currentDay);
        return identified;
    }

    public void clearSightings() {
        sightings.clear();
        localFallbacks.clear();
        divanCentre = null;
    }

    public boolean clearRemoteSightings() {
        return expireRemoteSightings(Long.MAX_VALUE);
    }

    public boolean expireRemoteSightings(long before) {
        boolean changed = false;
        for (int index = sightings.size() - 1; index >= 0; index--) {
            StructureSighting sighting = sightings.get(index);
            if (sighting.confidence() != SightingConfidence.SHARED_REMOTE || sighting.atMillis() >= before) continue;
            StructureSighting local = localFallbacks.remove(sighting);
            if (local == null) sightings.remove(index);
            else sightings.set(index, local);
            changed = true;
        }
        return changed;
    }

    private static MergeResult mergeAt(List<StructureSighting> sightings, int index,
                                       StructureSighting existing, StructureSighting incoming) {
        if (existing.confidence() == SightingConfidence.MANUAL
                && incoming.confidence() != SightingConfidence.MANUAL) {
            return MergeResult.IGNORED;
        }
        boolean incomingRemote = incoming.confidence() == SightingConfidence.SHARED_REMOTE;
        boolean existingRemote = existing.confidence() == SightingConfidence.SHARED_REMOTE;
        int confidence = Integer.compare(evidenceStrength(incoming), evidenceStrength(existing));
        // A peer's claimed detection cannot displace our own direct observation.
        if (confidence == 0 && incomingRemote != existingRemote) {
            confidence = incomingRemote ? -1 : 1;
        }
        if (confidence > 0) {
            sightings.set(index, incoming);
            return MergeResult.UPGRADED;
        }
        if (confidence == 0) {
            if (incomingRemote) {
                if (samePosition(existing, incoming) && incoming.atMillis() > existing.atMillis()) {
                    sightings.set(index, incoming);
                    return MergeResult.REFINED;
                }
                return MergeResult.IGNORED;
            }
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
            StructureSighting fallback = localFallbacks.get(existing);
            if (!incoming.structure().multiInstance()
                    || existing.position().distanceSquared(incoming.position())
                            <= MULTI_INSTANCE_DISTANCE_SQUARED
                    || incoming.confidence() != SightingConfidence.SHARED_REMOTE && fallback != null
                            && fallback.position().distanceSquared(incoming.position())
                                    <= MULTI_INSTANCE_DISTANCE_SQUARED) {
                return index;
            }
        }
        return -1;
    }

    private static int evidenceStrength(StructureSighting sighting) {
        return (sighting.remoteEvidence() == null ? sighting.confidence()
                : sighting.remoteEvidence()).ordinal();
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
