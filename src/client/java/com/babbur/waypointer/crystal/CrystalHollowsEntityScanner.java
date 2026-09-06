package com.babbur.waypointer.crystal;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Finds structures using nearby visible entities. */
public final class CrystalHollowsEntityScanner {

    public record Detection(int entityId, CrystalHollowsStructure structure,
                            CrystalHollowsPosition position, boolean divanKeeper,
                            String sourceName) {}

    private CrystalHollowsEntityScanner() {}

    public static java.util.List<Detection> scan(
            Minecraft client, CrystalHollowsStructure sidebarStructure, Set<Integer> processedIds) {
        if (client.level == null || client.player == null) return java.util.List.of();
        Set<String> playerNames = onlinePlayerNames(client.getConnection());
        java.util.List<Detection> detections = new java.util.ArrayList<>();
        Vec3 playerEye = client.player.getEyePosition();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (processedIds.contains(entity.getId())) continue;
            Vec3 target = entity.getEyePosition();
            double distance = playerEye.distanceTo(target);
            if (distance > EntityVisibility.MAX_DISTANCE) continue;
            if (MagmaCubeCompat.isLargeMagmaCube(entity)) {
                boolean matchesBal = sidebarStructure == CrystalHollowsStructure.KHAZAD_DUM;
                if (CrystalHollowsGeometry.insideHollows(
                                entity.getX(), entity.getY(), entity.getZ())
                        && EntityVisibility.shouldAccept(
                                distance, matchesBal, false,
                                () -> hasLineOfSight(client, target))) {
                    detections.add(detection(entity, CrystalHollowsStructure.KHAZAD_DUM,
                            0, 0, 0, false, "Bal"));
                }
                continue;
            }
            Optional<NamedAnchor> anchor = namedAnchor(entity, playerNames);
            boolean sidebarSame = anchor.isPresent()
                    && sidebarStructure == anchor.orElseThrow().match().structure();
            if (anchor.isEmpty()
                    || !CrystalHollowsGeometry.insideHollows(
                            entity.getX(), entity.getY(), entity.getZ())) continue;
            if (!EntityVisibility.shouldAccept(
                    distance,
                    anchor.isPresent(),
                    sidebarSame,
                    () -> hasLineOfSight(client, target))) continue;
            CrystalHollowsEntityAnchor.Match match = anchor.orElseThrow().match();
            detections.add(detection(entity, match.structure(), match.offsetX(), match.offsetY(),
                    match.offsetZ(), match.divanKeeper(), anchor.orElseThrow().name()));
        }
        return java.util.List.copyOf(detections);
    }

    /** Dialogue confirms proximity, so skip the visibility test. */
    public static Optional<CrystalHollowsPosition> nearestDialogueAnchor(
            Minecraft client, CrystalHollowsStructure structure) {
        if (client.level == null || client.player == null) return Optional.empty();
        Set<String> playerNames = onlinePlayerNames(client.getConnection());
        double nearestDistanceSquared = 16.0 * 16.0;
        CrystalHollowsPosition nearest = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            double distanceSquared = client.player.position().distanceToSqr(entity.position());
            if (distanceSquared > nearestDistanceSquared) continue;
            Optional<NamedAnchor> anchor = namedAnchor(entity, playerNames);
            if (anchor.isEmpty() || anchor.orElseThrow().match().structure() != structure) continue;
            CrystalHollowsEntityAnchor.Match match = anchor.orElseThrow().match();
            nearestDistanceSquared = distanceSquared;
            nearest = position(entity, match.offsetX(), match.offsetY(), match.offsetZ());
        }
        return Optional.ofNullable(nearest);
    }

    private static boolean hasLineOfSight(Minecraft client, Vec3 target) {
        if (client.level == null || client.player == null) return false;
        HitResult hit = client.level.clip(new ClipContext(
                client.player.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static Optional<NamedAnchor> namedAnchor(Entity entity, Set<String> playerNames) {
        if (entity.getCustomName() != null) {
            String name = entity.getCustomName().getString();
            Optional<CrystalHollowsEntityAnchor.Match> match = CrystalHollowsEntityAnchor.match(name);
            if (match.isPresent() && !playerNames.contains(cleanName(name))) {
                return Optional.of(new NamedAnchor(name, match.orElseThrow()));
            }
        }
        if (!(entity instanceof Player)) return Optional.empty();
        Player player = (Player) entity;
        if (!playerNames.contains(cleanName(player.getName().getString()))
                && CrystalHollowsEntityAnchor.isCorleoneProfile(player.getName().getString(),
                        player.getAttributeBaseValue(Attributes.MAX_HEALTH))) {
            return Optional.of(new NamedAnchor("Boss Corleone",
                    CrystalHollowsEntityAnchor.match("Boss Corleone").orElseThrow()));
        }
        String[] names = {entity.getName().getString(), entity.getDisplayName().getString()};
        for (String name : names) {
            Optional<CrystalHollowsEntityAnchor.Match> match = CrystalHollowsEntityAnchor.match(name);
            if (match.isPresent() && !playerNames.contains(cleanName(name))) {
                return Optional.of(new NamedAnchor(name, match.orElseThrow()));
            }
        }
        return Optional.empty();
    }

    private static Set<String> onlinePlayerNames(ClientPacketListener connection) {
        if (connection == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            names.add(cleanName(info.getProfile().name()));
        }
        return names;
    }

    private static String cleanName(String name) {
        return CrystalHollowsSidebar.stripFormatting(name).trim();
    }

    private static Detection detection(Entity entity, CrystalHollowsStructure structure,
                                       int offsetX, int offsetY, int offsetZ,
                                       boolean keeper, String sourceName) {
        return new Detection(entity.getId(), structure,
                position(entity, offsetX, offsetY, offsetZ), keeper, sourceName);
    }

    private static CrystalHollowsPosition position(
            Entity entity, int offsetX, int offsetY, int offsetZ) {
        return new CrystalHollowsPosition(
                Mth.floor(entity.getX()) + offsetX,
                Mth.floor(entity.getY()) + offsetY,
                Mth.floor(entity.getZ()) + offsetZ);
    }

    private record NamedAnchor(String name, CrystalHollowsEntityAnchor.Match match) {}
}
