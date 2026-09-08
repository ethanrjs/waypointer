package com.babbur.waypointer.diana;

import com.babbur.waypointer.crystal.compass.Vec3d;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Server particle evidence and lifecycle, independent of rendering and Minecraft. */
public final class DianaBurrows {
    public enum Particle { ENCHANTED_HIT, CRIT, DRIPPING_LAVA, LARGE_SMOKE, OTHER }
    public enum Kind { START, MOB, TREASURE, ESTIMATE }
    public record Block(int x, int y, int z) {
        public static Block at(double x, double y, double z) {
            return new Block((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
        double distanceTo(Block other) {
            return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
        }
    }
    public record Marker(Block block, Kind kind) {}
    private record Seen(Kind kind, long at) {}
    private static final Pattern PROGRESS = Pattern.compile("^You .* Griffin [Bb]urrow.*\\(\\d+/\\d+\\)$");
    private final Map<Block, Seen> known = new LinkedHashMap<>();
    private final Map<Block, Long> removed = new LinkedHashMap<>();
    private final DianaSpadeSolver solver = new DianaSpadeSolver();
    private Block clicked;
    private long clickedAt;
    private Block estimate;
    private long estimatedAt;

    public void use(Vec3d player, long now) {
        if (solver.use(player, now)) estimate = null;
    }

    public void click(Block block, long now) {
        clicked = block;
        clickedAt = now;
    }

    public void particle(Particle type, double x, double y, double z, int count,
                         float speed, float dx, float dy, float dz, long now) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 1_024 || Math.abs(z) > 1_024 || y < 0 || y > 256) return;
        if (type == Particle.DRIPPING_LAVA && count == 2 && speed == -0.5f) {
            if (!solver.accept(new Vec3d(x, y, z), now)) return;
            Vec3d result = solver.estimate();
            if (result == null) {
                estimate = null;
                return;
            }
            Block next = Block.at(result.x(), result.y() - 0.5, result.z());
            if (known.keySet().stream().noneMatch(block -> block.distanceTo(next) <= 3)
                    && !recentlyRemoved(next, now)) {
                estimate = next;
                estimatedAt = now;
            } else estimate = null;
            return;
        }
        Block block = Block.at(x, y - 1, z);
        if (type == Particle.LARGE_SMOKE && speed == 0.01f && dx == 0 && dy == 0 && dz == 0) {
            remove(block, now);
            return;
        }
        Kind kind = classify(type, count, speed, dx, dy, dz);
        if (kind == null || recentlyRemoved(block, now)) return;
        // Bounds cover far more markers than one event can reasonably expose.
        if (!known.containsKey(block) && known.size() >= 128) return;
        known.put(block, new Seen(kind, now));
        if (estimate != null && estimate.distanceTo(block) <= 3) {
            estimate = null;
            solver.reset();
        }
    }

    public static Kind classify(Particle type, int count, float speed, float dx, float dy, float dz) {
        if (speed != 0.01f || !close(dy, 0.1f)) return null;
        if (type == Particle.DRIPPING_LAVA && count == 2 && close(dx, 0.35f) && close(dz, 0.35f)) {
            return Kind.TREASURE;
        }
        if (!close(dx, 0.5f) || !close(dz, 0.5f)) return null;
        if (type == Particle.ENCHANTED_HIT && count == 4) return Kind.START;
        return type == Particle.CRIT && count == 3 ? Kind.MOB : null;
    }

    private static boolean close(float value, float expected) {
        return Math.abs(value - expected) < 0.005f;
    }

    public void message(String text, long now) {
        if (text == null) return;
        boolean finished = text.startsWith("You finished the Griffin burrow chain!");
        if (finished || PROGRESS.matcher(text).matches()) {
            if (clicked != null && now - clickedAt <= 30_000) remove(clicked, now);
            clicked = null;
            estimate = null;
            solver.reset();
        }
        // A mob/treasure spawn is only the FIRST dig. Keep its marker until
        // the server's progress/chain-complete message or removal particles.
    }

    public void expire(long now) {
        known.entrySet().removeIf(entry -> now - entry.getValue().at() > 1_800_000);
        removed.entrySet().removeIf(entry -> now - entry.getValue() > 2_000);
        if (estimate != null && now - estimatedAt > 30_000) {
            estimate = null;
            solver.reset();
        }
    }

    private boolean recentlyRemoved(Block block, long now) {
        Long at = removed.get(block);
        return at != null && now - at <= 2_000;
    }

    private void remove(Block block, long now) {
        known.remove(block);
        if (removed.size() >= 128) removed.remove(removed.keySet().iterator().next());
        removed.put(block, now);
        if (estimate != null && estimate.distanceTo(block) <= 3) {
            estimate = null;
            solver.reset();
        }
    }

    public List<Marker> markers() {
        var markers = new java.util.ArrayList<Marker>();
        known.forEach((block, seen) -> markers.add(new Marker(block, seen.kind())));
        if (estimate != null) markers.add(new Marker(estimate, Kind.ESTIMATE));
        return List.copyOf(markers);
    }

    public void reset() {
        known.clear();
        removed.clear();
        solver.reset();
        clicked = estimate = null;
    }
}
