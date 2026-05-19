package dev.ethan.waypointer.diana;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.math.DistanceUtil;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DianaBurrowDetector {

    private static final String HUB_ZONE_ID = "hub";
    private static final int WARP_HELPFUL_COLOR = 0x3FA7FF;
    private static final int HUB_MIN_X = -296;
    private static final int HUB_MAX_X = 207;
    private static final int MIN_BURROW_Y = 45;
    private static final int MAX_BURROW_Y = 140;
    private static final int HUB_MIN_Z = -272;
    private static final int HUB_MAX_Z = 223;
    private static final long DETECTED_STALE_MS = 2_500L;
    private static final long GUESS_EXPIRE_MS = 30L * 60L * 1000L;
    private static final long ARROW_POINT_TTL_MS = 2_000L;
    private static final long RECENT_ARROW_TTL_MS = 18_000L;
    private static final long RECENT_BURROW_CLICK_MS = 5_000L;
    private static final long RECENT_BURROW_ARROW_MS = 6_000L;
    private static final long RECENT_ARROW_CLICK_SOURCE_MS = 12_000L;
    private static final long RECENT_SPADE_SEARCH_ARROW_MS = 12_000L;
    private static final long SPADE_SEARCH_DEBOUNCE_MS = 250L;
    private static final long SPADE_ECHO_WINDOW_MS = 4_000L;
    private static final long SPADE_CURVE_POINT_TTL_MS = 4_000L;
    private static final int ARROW_SHAFT_POINTS = 20;
    private static final int MAX_ARROW_POINTS = 96;
    private static final int MAX_SPADE_CURVE_POINTS = 128;
    private static final double ARROW_POINT_TOLERANCE = 0.12;
    private static final double SPADE_CURVE_DUPLICATE_TOLERANCE = 0.01;
    private static final double SPADE_CURVE_OUTLIER_DISTANCE = 8.0;
    private static final int SPADE_CURVE_STABLE_ESTIMATE_COUNT = 3;
    private static final int ESTIMATE_GROUND_SCAN_ABOVE = 6;
    private static final double RESOLVED_BURROW_HORIZONTAL_RADIUS = 6.0;
    private static final int RESOLVED_BURROW_Y_TOLERANCE = 16;
    private static final double EPSILON = 1.0E-6;
    private static final Pattern CHAIN_PROGRESS_PATTERN = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

    /**
     * Second line on Diana estimate markers when warp-assist applies. Kept in this
     * class with {@link #dianaEstimateNameWithWarpAssistLine} so keybind lookup
     * stays aligned with the detector label.
     */
    public static final String DIANA_WARP_ASSIST_HINT = "Press warp key";

    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final Map<BlockKey, Sighting> sightings = new ConcurrentHashMap<>();
    private final Map<BlockKey, Guess> guesses = new ConcurrentHashMap<>();
    private final List<TimedPoint> arrowPoints = new ArrayList<>();
    private final List<TimedPoint> spadeCurvePoints = new ArrayList<>();
    private final List<BlockKey> recentSpadeCurveEstimates = new ArrayList<>();
    private final List<TimedRay> recentArrows = new ArrayList<>();
    private final Map<String, Long> spadeDebugLogTimes = new HashMap<>();
    private String lastRenderedSignature = "";
    private long lastBurrowRelatedChatMillis;
    private BlockKey lastSpadeAttackBlock;
    private long lastSpadeAttackMillis;
    private long lastSpadeSearchMillis;
    private int spadeEchoParticlesSinceClick;
    private boolean reportedSpadeEchoForClick;
    private boolean reportedArrowParticlesForSequence;
    private boolean currentChainComplete = true;
    private int tickCounter;

    public DianaBurrowDetector(WaypointerConfig config, ActiveGroupManager manager) {
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        WaypointerParticleEvents.register(this::onParticle);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                onTick();
            } catch (RuntimeException e) {
                Waypointer.LOGGER.warn("Waypointer Diana detector tick failed; skipping this tick", e);
            }
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                onSpadeSearch(player.getItemInHand(hand), null);
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) {
                onSpadeSearch(player.getItemInHand(hand), hit.getBlockPos());
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) {
                onSpadeAttack(player.getItemInHand(hand), pos);
            }
            return InteractionResult.PASS;
        });
    }

    private void onSpadeSearch(ItemStack item, BlockPos pos) {
        if (!isEnabledInHub() || !isDianaSpade(item)) return;

        long now = System.currentTimeMillis();
        if (now - lastSpadeSearchMillis < SPADE_SEARCH_DEBOUNCE_MS) return;

        lastSpadeSearchMillis = now;
        spadeEchoParticlesSinceClick = 0;
        reportedSpadeEchoForClick = false;
        reportedArrowParticlesForSequence = false;
        arrowPoints.clear();
        spadeCurvePoints.clear();
        recentSpadeCurveEstimates.clear();
        // Keep the existing estimate visible while the new spade curve settles.
        // It will be replaced atomically once the new estimate is stable.
        debugSpade("search", 0L,
                "search click registered"
                        + (pos == null ? "" : " near " + format(pos.getX(), pos.getY(), pos.getZ()))
                        + "; cleared samples, keeping current estimate until a new one stabilizes");
    }

    private void onSpadeAttack(ItemStack item, BlockPos pos) {
        if (!isEnabledInHub() || !isDianaSpade(item) || pos == null) return;

        lastSpadeAttackBlock = new BlockKey(pos.getX(), pos.getY(), pos.getZ());
        lastSpadeAttackMillis = System.currentTimeMillis();
        debugSpade("attack", 250L, "left-click burrow check at " + lastSpadeAttackBlock.format());
    }

    private Component onMessage(Component message, boolean overlay) {
        if (overlay || !config.dianaBurrowWaypoints()) return message;
        String clean = message.getString();
        if (clean.contains("Poof!") && clean.contains("cleared your griffin burrows")) {
            reset();
            return message;
        }
        boolean chainStateChanged = updateChainState(clean);
        if (isBurrowDugMessage(clean)) {
            lastBurrowRelatedChatMillis = System.currentTimeMillis();
            BlockKey clicked = recentlyClickedBurrowBlock(lastBurrowRelatedChatMillis);
            if (clicked != null) {
                removeNearby(clicked, 3.0);
            } else {
                removeNearestToPlayer(9.0);
            }
            clearResolvedBurrowMarkers(clean);
            guesses.clear();
            renderWaypoints();
        } else if (chainStateChanged) {
            renderWaypoints();
        }
        return message;
    }

    private void onParticle(ClientboundLevelParticlesPacket packet) {
        if (!isEnabledInHub()) return;

        long now = System.currentTimeMillis();
        ParticleOptions particle = packet.getParticle();
        if (particle.getType() == ParticleTypes.DUST) {
            handleArrowParticle(packet, now);
            return;
        }
        if (isSpadeEchoParticle(packet)) {
            handleSpadeEchoParticle(packet, now);
            return;
        }

        DianaBurrowType type = classifyBurrowParticle(packet);
        if (type == null && particle.getType() != ParticleTypes.ENCHANT) return;

        BlockKey key = burrowBlock(packet);
        Sighting sighting = sightings.computeIfAbsent(key, ignored -> new Sighting());
        if (particle.getType() == ParticleTypes.ENCHANT) {
            sighting.hasEnchant = true;
        } else {
            sighting.type = type;
        }
        sighting.lastSeenMillis = now;

        if (sighting.hasEnchant && sighting.type != null && sighting.confirmedType != sighting.type) {
            sighting.confirmedType = sighting.type;
            removeNearbyGuesses(key, 45.0);
            renderWaypoints();
        }
    }

    private void onTick() {
        if (++tickCounter < 10) return;
        tickCounter = 0;

        if (!isEnabledInHub()) {
            if (!sightings.isEmpty() || !guesses.isEmpty()) reset();
            return;
        }

        long now = System.currentTimeMillis();
        boolean changed = prune(now);
        if (changed || (!guesses.isEmpty() && config.dianaWarpAssist())) {
            renderWaypoints();
        }
    }

    private boolean prune(long now) {
        boolean changed = false;
        Iterator<Map.Entry<BlockKey, Sighting>> sightingIt = sightings.entrySet().iterator();
        while (sightingIt.hasNext()) {
            Map.Entry<BlockKey, Sighting> entry = sightingIt.next();
            Sighting sighting = entry.getValue();
            long staleAfter = sighting.confirmedType == null
                    ? DETECTED_STALE_MS
                    : GUESS_EXPIRE_MS;
            if (now - sighting.lastSeenMillis > staleAfter) {
                sightingIt.remove();
                changed = true;
            }
        }

        Iterator<Map.Entry<BlockKey, Guess>> guessIt = guesses.entrySet().iterator();
        while (guessIt.hasNext()) {
            Map.Entry<BlockKey, Guess> entry = guessIt.next();
            if (now - entry.getValue().createdAtMillis > GUESS_EXPIRE_MS) {
                guessIt.remove();
                changed = true;
            }
        }
        if (normalizeLoadedGuesses()) changed = true;

        arrowPoints.removeIf(point -> now - point.timeMillis() > ARROW_POINT_TTL_MS);
        spadeCurvePoints.removeIf(point -> now - point.timeMillis() > SPADE_CURVE_POINT_TTL_MS);
        recentArrows.removeIf(ray -> now - ray.timeMillis() > RECENT_ARROW_TTL_MS);
        return changed;
    }

    private DianaBurrowType classifyBurrowParticle(ClientboundLevelParticlesPacket packet) {
        ParticleOptions particle = packet.getParticle();
        if (particle.getType() == ParticleTypes.ENCHANTED_HIT
                && packet.getCount() == 4
                && close(packet.getMaxSpeed(), 0.01)
                && close(packet.getXDist(), 0.5)
                && close(packet.getYDist(), 0.1)
                && close(packet.getZDist(), 0.5)) {
            return DianaBurrowType.START;
        }
        if (particle.getType() == ParticleTypes.CRIT
                && packet.getCount() == 3
                && close(packet.getMaxSpeed(), 0.01)
                && close(packet.getXDist(), 0.5)
                && close(packet.getYDist(), 0.1)
                && close(packet.getZDist(), 0.5)) {
            return DianaBurrowType.MOB;
        }
        if (particle.getType() == ParticleTypes.DRIPPING_LAVA
                && packet.getCount() == 2
                && close(packet.getMaxSpeed(), 0.01)
                && close(packet.getXDist(), 0.35)
                && close(packet.getYDist(), 0.1)
                && close(packet.getZDist(), 0.35)) {
            return DianaBurrowType.TREASURE;
        }
        return null;
    }

    private boolean isSpadeEchoParticle(ClientboundLevelParticlesPacket packet) {
        return packet.getParticle().getType() == ParticleTypes.DRIPPING_LAVA
                && ((packet.getCount() == 2 && close(packet.getMaxSpeed(), -0.5))
                || (close(packet.getXDist(), 0.0)
                && close(packet.getYDist(), 0.0)
                && close(packet.getZDist(), 0.0)));
    }

    private void handleSpadeEchoParticle(ClientboundLevelParticlesPacket packet, long now) {
        if (now - lastSpadeSearchMillis > SPADE_ECHO_WINDOW_MS) return;

        spadeEchoParticlesSinceClick++;
        if (isSpadeCurveParticle(packet) && addSpadeCurvePoint(packet, now)) {
            refineGuessFromSpadeCurve(now);
        }
        if (!reportedSpadeEchoForClick) {
            reportedSpadeEchoForClick = true;
            debugSpade("echo-first", 0L,
                    "first spade echo particle: type=" + packet.getParticle().getType()
                            + " count=" + packet.getCount()
                            + " speed=" + packet.getMaxSpeed()
                            + " offset=(" + packet.getXDist() + ", "
                            + packet.getYDist() + ", " + packet.getZDist() + ")");
        }
    }

    private void handleArrowParticle(ClientboundLevelParticlesPacket packet, long now) {
        if (packet.getCount() != 0 || !close(packet.getMaxSpeed(), 1.0)) return;
        IntRange range = arrowRange(packet);
        if (range == null) return;
        if (distanceToPlayerSq(packet.getX(), packet.getY(), packet.getZ()) > 36.0) return;
        if (!isArrowNearRecentBurrowClick(packet, now)) return;
        if (hasActiveSpadeCurve(now)) return;

        if (!reportedArrowParticlesForSequence) {
            reportedArrowParticlesForSequence = true;
            debugSpade("arrow-first", 0L,
                    "arrow dust detected; range bucket " + range.min() + "-" + range.max());
        }

        arrowPoints.add(new TimedPoint(new Vec3(packet.getX(), packet.getY(), packet.getZ()), now));
        arrowPoints.removeIf(point -> now - point.timeMillis() > ARROW_POINT_TTL_MS);
        trimOldest(arrowPoints, MAX_ARROW_POINTS);

        Ray ray = detectArrowRay();
        if (ray == null) return;
        if (markFirstArrowSighting(ray, now)) return;
        arrowPoints.clear();

        if (!hasRecentArrowContext(now)) {
            debugSpade("arrow-no-context", 2_500L,
                    "arrow ray ignored because no recent burrow dig or spade search context exists");
            return;
        }

        BlockKey guess = guessFromArrow(ray, range);
        if (guess == null) {
            debugSpade("arrow-no-candidate", 2_500L,
                    "arrow ray had no valid burrow block candidate for range "
                            + range.min() + "-" + range.max());
            return;
        }
        if (distanceToPlayerSq(guess.x() + 0.5, guess.y() + 0.5, guess.z() + 0.5) < 36.0) {
            debugSpade("arrow-too-close", 2_500L,
                    "arrow estimate rejected as too close at " + guess.format());
            return;
        }
        setEstimateGuess(guess, now, EstimateSource.ARROW);
    }

    private IntRange arrowRange(ClientboundLevelParticlesPacket packet) {
        int x = Math.round(packet.getXDist());
        int y = Math.round(packet.getYDist());
        int z = Math.round(packet.getZDist());
        if (x == 0 && y == 128 && z == 0) return new IntRange(0, 117);
        if (x == 255 && y == 255 && z == 0) return new IntRange(112, 282);
        if (x == 255 && y == 0 && z == 0) return new IntRange(281, 600);
        return null;
    }

    private Ray detectArrowRay() {
        List<TimedPoint> snapshot = List.copyOf(arrowPoints);
        List<Vec3> points = snapshot.stream().map(TimedPoint::point).toList();
        List<Vec3> line = findLine(points);
        if (line.isEmpty()) return null;

        int count1 = countWithin(points, line.get(1), ARROW_POINT_TOLERANCE);
        int count2 = countWithin(points, line.get(line.size() - 2), ARROW_POINT_TOLERANCE);
        if (!((count1 == 2 && count2 == 4) || (count1 == 4 && count2 == 2))) return null;

        Vec3 tip;
        Vec3 base;
        if (count1 == 4) {
            tip = line.get(0);
            base = line.get(line.size() - 1);
        } else {
            tip = line.get(line.size() - 1);
            base = line.get(0);
        }

        Vec3 origin = base.add(0.0, -1.5, 0.0);
        Vec3 direction = tip.add(0.0, -1.5, 0.0).subtract(origin).normalize();
        if (direction.lengthSquared() < EPSILON) return null;
        return new Ray(origin, direction);
    }

    private boolean markFirstArrowSighting(Ray ray, long now) {
        for (TimedRay existing : recentArrows) {
            if (existing.ray().sameRay(ray)) return false;
        }
        recentArrows.add(new TimedRay(ray, now));
        return true;
    }

    private BlockKey guessFromArrow(Ray ray, IntRange range) {
        Map<BlockKey, Candidate> candidates = new HashMap<>();
        for (int distance = Math.max(1, range.min()); distance <= range.max(); distance++) {
            Vec3 point = ray.origin().add(ray.direction().scale(distance));
            BlockKey block = new BlockKey(blockCoord(point.x()), blockCoord(point.y()), blockCoord(point.z()));
            if (!isValidBurrowBlock(block)) continue;

            Vec3 center = new Vec3(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
            double distToRay = distanceToRay(ray, center);
            double scaled = distToRay * 500_000.0 / Math.max(1.0, distance);
            Candidate existing = candidates.get(block);
            if (existing == null || scaled < existing.scaledDistance()) {
                candidates.put(block, new Candidate(scaled, distance));
            }
        }

        return candidates.entrySet().stream()
                .min(Comparator
                        .comparingDouble((Map.Entry<BlockKey, Candidate> entry) -> entry.getValue().scaledDistance())
                        .thenComparingDouble(entry -> entry.getValue().distanceFromOrigin()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private List<Vec3> findLine(List<Vec3> points) {
        for (Vec3 point : points) {
            List<Vec3> line = new ArrayList<>();
            List<Vec3> visited = new ArrayList<>();
            line.add(point);
            visited.add(point);
            if (extendLine(line, visited, points)) return List.copyOf(line);
        }
        return List.of();
    }

    private boolean extendLine(List<Vec3> line, List<Vec3> visited, List<Vec3> points) {
        if (line.size() == ARROW_SHAFT_POINTS) return true;

        Vec3 next = null;
        double minDist = Double.MAX_VALUE;
        for (Vec3 point : points) {
            if (visited.contains(point)) continue;
            double dist = line.get(line.size() - 1).distance(point);
            if (dist > ARROW_POINT_TOLERANCE) continue;

            Vec3 second = line.size() > 1 ? line.get(1) : line.get(0);
            if (!isCollinear(line.get(0), second, point)) continue;

            if (dist < minDist) {
                minDist = dist;
                next = point;
            }
        }

        if (next == null) return false;
        line.add(next);
        visited.add(next);
        if (extendLine(line, visited, points)) return true;
        line.remove(line.size() - 1);
        visited.remove(next);
        return false;
    }

    private static boolean isCollinear(Vec3 a, Vec3 b, Vec3 c) {
        return b.subtract(a).cross(c.subtract(a)).lengthSquared() < EPSILON;
    }

    private static int countWithin(List<Vec3> points, Vec3 origin, double maxDist) {
        double maxDistSq = maxDist * maxDist;
        int count = 0;
        for (Vec3 point : points) {
            if (!point.equals(origin) && point.distanceSquared(origin) <= maxDistSq) count++;
        }
        return count;
    }

    private boolean isValidBurrowBlock(BlockKey block) {
        if (!isPlausibleBurrowY(block.y())) return false;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return false;

        BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
        if (!level.isLoaded(pos)) return true;

        BlockState ground = level.getBlockState(pos);
        if (!ground.is(Blocks.GRASS_BLOCK)) return false;

        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.getCollisionShape(level, pos.above()).isEmpty();
    }

    private boolean isLoadedValidBurrowBlock(BlockKey block) {
        if (!isPlausibleBurrowY(block.y())) return false;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return false;

        BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
        return level.isLoaded(pos) && isValidBurrowBlock(block);
    }

    private boolean isReasonableEstimateBlock(BlockKey block) {
        return normalizeEstimateBlock(block) != null;
    }

    private BlockKey normalizeEstimateBlock(BlockKey block) {
        if (!isPlausibleHubColumn(block)) return null;

        BlockKey loadedGround = loadedBurrowGround(block);
        if (loadedGround != null) return loadedGround;
        if (isColumnLoaded(block)) return null;

        return isPlausibleBurrowY(block.y()) ? block : null;
    }

    private BlockKey loadedBurrowGround(BlockKey block) {
        if (!isColumnLoaded(block)) return null;

        int startY = Math.min(MAX_BURROW_Y, block.y() + ESTIMATE_GROUND_SCAN_ABOVE);
        for (int y = startY; y >= MIN_BURROW_Y; y--) {
            BlockKey candidate = new BlockKey(block.x(), y, block.z());
            if (isValidBurrowBlock(candidate)) return candidate;
        }
        return null;
    }

    private boolean isColumnLoaded(BlockKey block) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return false;

        int y = Math.max(MIN_BURROW_Y, Math.min(MAX_BURROW_Y, block.y()));
        return level.isLoaded(new BlockPos(block.x(), y, block.z()));
    }

    private boolean addSpadeCurvePoint(ClientboundLevelParticlesPacket packet, long now) {
        Vec3 point = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        List<TimedPoint> snapshot = List.copyOf(spadeCurvePoints);
        if (!snapshot.isEmpty()) {
            Vec3 previous = snapshot.get(snapshot.size() - 1).point();
            double distance = previous.distance(point);
            if (distance < SPADE_CURVE_DUPLICATE_TOLERANCE) return false;
            if (distance > SPADE_CURVE_OUTLIER_DISTANCE) {
                spadeCurvePoints.clear();
                recentSpadeCurveEstimates.clear();
                debugSpade("curve-outlier", 1_000L,
                        "curve sample jumped " + formatDistance(distance)
                                + " blocks; reset curve fit at "
                                + formatPoint(point));
            }
        }

        spadeCurvePoints.add(new TimedPoint(point, now));
        spadeCurvePoints.removeIf(sample -> now - sample.timeMillis() > SPADE_CURVE_POINT_TTL_MS);
        trimOldest(spadeCurvePoints, MAX_SPADE_CURVE_POINTS);
        int sampleCount = spadeCurvePoints.size();
        if (sampleCount == 1 || sampleCount % 8 == 0) {
            debugSpade("curve-samples", 750L,
                    "collected " + sampleCount + " spade curve sample(s); latest "
                            + formatPoint(point));
        }
        return true;
    }

    private void refineGuessFromSpadeCurve(long now) {
        List<DianaSpadeCurveSolver.Sample> samples = List.copyOf(spadeCurvePoints).stream()
                .map(point -> new DianaSpadeCurveSolver.Sample(
                        point.point().x(),
                        point.point().y(),
                        point.point().z()))
                .toList();
        var estimateOpt = DianaSpadeCurveSolver.estimate(samples);
        if (estimateOpt.isEmpty()) {
            debugSpade("curve-no-estimate", 1_000L,
                    "curve solver has no estimate yet from " + samples.size() + " sample(s)");
            return;
        }

        DianaSpadeCurveSolver.Estimate estimate = estimateOpt.get();
        BlockKey rawGuess = new BlockKey(
                blockCoord(estimate.x()),
                blockCoord(estimate.y() - 0.5),
                blockCoord(estimate.z()));
        BlockKey guess = normalizeEstimateBlock(rawGuess);
        if (guess == null) {
            debugSpade("curve-invalid", 1_500L,
                    "curve estimate rejected outside valid Hub burrow space at " + rawGuess.format()
                            + " from " + estimate.sampleCount() + " sample(s)");
            return;
        }
        if (distanceToPlayerSq(guess.x() + 0.5, guess.y() + 0.5, guess.z() + 0.5) < 9.0) {
            debugSpade("curve-too-close", 1_500L,
                    "curve estimate rejected as too close at " + guess.format());
            return;
        }
        if (!isStableSpadeCurveEstimate(guess, estimate.sampleCount())) {
            debugSpade("curve-unstable", 750L,
                    "curve estimate still stabilizing at " + guess.format()
                            + " from " + estimate.sampleCount() + " sample(s)");
            return;
        }

        debugSpade("curve-accepted", 0L,
                "curve estimate accepted at " + guess.format()
                        + " from " + estimate.sampleCount() + " sample(s)");
        setEstimateGuess(guess, now, EstimateSource.SPADE_CURVE);
    }

    private void setEstimateGuess(BlockKey guess, long now, EstimateSource source) {
        if (!config.dianaSpadeEstimateWaypoints()) {
            debugSpade("estimate-disabled", 1_500L, "estimate ignored because estimate waypoints are disabled");
            return;
        }
        if (source == EstimateSource.ARROW && currentEstimateSource() == EstimateSource.SPADE_CURVE) {
            debugSpade("arrow-ignored-curve-active", 1_500L,
                    "arrow estimate ignored because a spade-curve estimate is active");
            return;
        }
        guess = normalizeEstimateBlock(guess);
        if (guess == null) {
            debugSpade("estimate-normalize-null", 1_500L,
                    "estimate ignored because ground normalization failed");
            return;
        }
        guesses.clear();
        guesses.put(guess, new Guess(now, source));
        renderWaypoints();
    }

    private boolean isStableSpadeCurveEstimate(BlockKey guess, int sampleCount) {
        recentSpadeCurveEstimates.add(guess);
        while (recentSpadeCurveEstimates.size() > SPADE_CURVE_STABLE_ESTIMATE_COUNT) {
            recentSpadeCurveEstimates.remove(0);
        }

        if (sampleCount < config.dianaEstimateMinSamples()
                || recentSpadeCurveEstimates.size() < SPADE_CURVE_STABLE_ESTIMATE_COUNT) {
            return false;
        }

        double stableRadius = config.dianaEstimateStabilityRadius();
        double stableRadiusSq = stableRadius * stableRadius;
        for (BlockKey candidate : recentSpadeCurveEstimates) {
            if (candidate.distanceSq(guess) > stableRadiusSq) return false;
        }
        return true;
    }

    private EstimateSource currentEstimateSource() {
        return guesses.values().stream()
                .findFirst()
                .map(Guess::source)
                .orElse(null);
    }

    private boolean normalizeLoadedGuesses() {
        boolean changed = false;
        for (Map.Entry<BlockKey, Guess> entry : List.copyOf(guesses.entrySet())) {
            BlockKey key = entry.getKey();
            Guess guess = entry.getValue();
            BlockKey normalized = normalizeEstimateBlock(key);
            if (normalized == null) {
                if (guesses.remove(key, guess)) changed = true;
                continue;
            }
            if (!normalized.equals(key) && guesses.remove(key, guess)) {
                guesses.put(normalized, guess);
                changed = true;
            }
        }
        return changed;
    }

    private boolean isArrowNearRecentBurrowClick(ClientboundLevelParticlesPacket packet, long now) {
        if (lastSpadeAttackBlock == null || now - lastSpadeAttackMillis > RECENT_ARROW_CLICK_SOURCE_MS) {
            return false;
        }
        return lastSpadeAttackBlock.distanceSq(packet.getX(), packet.getY() - 2.0, packet.getZ()) <= 64.0;
    }

    private boolean hasActiveSpadeCurve(long now) {
        return !spadeCurvePoints.isEmpty()
                && now - spadeCurvePoints.get(spadeCurvePoints.size() - 1).timeMillis() <= SPADE_CURVE_POINT_TTL_MS;
    }

    private void removeNearbyGuesses(BlockKey key, double radius) {
        guesses.keySet().removeIf(guess -> sameBurrowColumnArea(guess, key, radius));
    }

    private BlockKey recentlyClickedBurrowBlock(long now) {
        if (lastSpadeAttackBlock == null) return null;
        return now - lastSpadeAttackMillis <= RECENT_BURROW_CLICK_MS ? lastSpadeAttackBlock : null;
    }

    private boolean removeNearby(BlockKey key, double radius) {
        boolean removedSighting = sightings.keySet().removeIf(candidate -> sameBurrowColumnArea(candidate, key, radius));
        boolean removedGuess = guesses.keySet().removeIf(candidate -> sameBurrowColumnArea(candidate, key, radius));
        return removedSighting || removedGuess;
    }

    private boolean clearResolvedBurrowMarkers(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        boolean progress = lower.contains("griffin burrow") || lower.contains("finished the griffin burrow chain");
        boolean dugMob = isMobDugMessage(lower);
        boolean dugTreasure = isTreasureDugMessage(lower);

        if (!progress && !dugMob && !dugTreasure) return false;

        boolean removed = false;
        if (progress || dugMob) removed |= removeSightingsOfType(DianaBurrowType.MOB);
        if (progress || dugTreasure) removed |= removeSightingsOfType(DianaBurrowType.TREASURE);
        return removed;
    }

    private boolean removeSightingsOfType(DianaBurrowType type) {
        return sightings.entrySet().removeIf(entry -> entry.getValue().confirmedType == type);
    }

    private static boolean sameBurrowColumnArea(BlockKey candidate, BlockKey target, double horizontalRadius) {
        double dx = candidate.x() - target.x();
        double dz = candidate.z() - target.z();
        return dx * dx + dz * dz <= horizontalRadius * horizontalRadius
                && Math.abs(candidate.y() - target.y()) <= RESOLVED_BURROW_Y_TOLERANCE;
    }

    private boolean removeNearestToPlayer(double radius) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        double radiusSq = radius * radius;
        BlockKey nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockKey key : allKnownKeys()) {
            double dist = key.distanceSq(player.getX(), player.getY(), player.getZ());
            if (dist <= radiusSq && dist < best) {
                best = dist;
                nearest = key;
            }
        }
        if (nearest != null) {
            sightings.remove(nearest);
            guesses.remove(nearest);
            return true;
        }
        return false;
    }

    private List<BlockKey> allKnownKeys() {
        List<BlockKey> out = new ArrayList<>(sightings.keySet());
        out.addAll(guesses.keySet());
        return out;
    }

    private void renderWaypoints() {
        if (!config.dianaBurrowWaypoints()) return;

        List<Waypoint> estimateWaypoints = new ArrayList<>();
        if (config.dianaSpadeEstimateWaypoints()) {
            guesses.keySet().stream()
                    .filter(key -> !sightings.containsKey(key))
                    .sorted()
                    .forEach(key -> estimateWaypoints.add(waypoint(key, DianaBurrowType.GUESS)));
        }

        List<Waypoint> waypoints = new ArrayList<>(estimateWaypoints);
        sightings.entrySet().stream()
                .filter(entry -> entry.getValue().confirmedType != null)
                .filter(entry -> shouldRenderDianaType(entry.getValue().confirmedType))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> waypoints.add(waypoint(entry.getKey(), entry.getValue().confirmedType)));

        int navigationIndex = navigationIndex(waypoints, estimateWaypoints.size());
        String signature = signature(waypoints);
        WaypointGroup existing = manager.get(DianaBurrowWaypointGroup.ID);
        if (Objects.equals(signature, lastRenderedSignature)) {
            if (existing != null && existing.currentIndex() != navigationIndex) {
                existing.setCurrentIndex(navigationIndex);
                manager.fireDataChanged();
            }
            return;
        }
        lastRenderedSignature = signature;

        if (waypoints.isEmpty()) {
            if (existing != null) manager.remove(DianaBurrowWaypointGroup.ID);
            return;
        }

        WaypointGroup group = ensureGroup();
        group.replaceWaypoints(waypoints);
        group.setCurrentIndex(navigationIndex);
        manager.fireDataChanged();
    }

    private int navigationIndex(List<Waypoint> waypoints, int estimateCount) {
        if (estimateCount > 0) return 0;
        if (!currentChainComplete) return waypoints.size();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return firstStartBurrowIndex(waypoints);

        int bestIndex = waypoints.size();
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint waypoint = waypoints.get(i);
            if (!DianaBurrowType.START.label().equals(waypoint.name())) continue;

            double distanceSq = waypointDistanceSq(waypoint, player.getX(), player.getY(), player.getZ());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int firstStartBurrowIndex(List<Waypoint> waypoints) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (DianaBurrowType.START.label().equals(waypoints.get(i).name())) return i;
        }
        return waypoints.size();
    }

    private static double waypointDistanceSq(Waypoint waypoint, double x, double y, double z) {
        double dx = waypoint.x() + 0.5 - x;
        double dy = waypoint.y() + 0.5 - y;
        double dz = waypoint.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private WaypointGroup ensureGroup() {
        WaypointGroup existing = manager.get(DianaBurrowWaypointGroup.ID);
        if (existing != null) return existing;

        WaypointGroup group = new WaypointGroup(DianaBurrowWaypointGroup.ID, "Diana Burrows", HUB_ZONE_ID);
        group.setTemp(true);
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setSkipAheadEnabled(false);
        manager.add(group);
        return group;
    }

    private Waypoint waypoint(BlockKey key, DianaBurrowType type) {
        return Waypoint.at(key.x(), key.y(), key.z())
                .withName(dianaLabel(key, type))
                .withColor(dianaColor(key, type))
                .withFlags(Waypoint.FLAG_LOCKED_COLOR)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L);
    }

    private String dianaLabel(BlockKey key, DianaBurrowType type) {
        if (type != DianaBurrowType.GUESS) return type.label();

        String base = config.dianaEstimateWaypointName();
        return hasHelpfulWarp(key) ? dianaEstimateNameWithWarpAssistLine(base) : base;
    }

    /** Full waypoint name when the warp-assist hint row is shown under {@code baseName}. */
    public static String dianaEstimateNameWithWarpAssistLine(String baseName) {
        return baseName + "\n" + DIANA_WARP_ASSIST_HINT;
    }

    private int dianaColor(BlockKey key, DianaBurrowType type) {
        if (type == DianaBurrowType.GUESS && hasHelpfulWarp(key)) {
            return WARP_HELPFUL_COLOR;
        }
        return switch (type) {
            case START -> config.dianaStartBurrowColor();
            case MOB -> config.dianaMobBurrowColor();
            case TREASURE -> config.dianaTreasureBurrowColor();
            case GUESS -> config.dianaEstimateWaypointColor();
        };
    }

    private static String signature(List<Waypoint> waypoints) {
        StringBuilder out = new StringBuilder();
        for (Waypoint waypoint : waypoints) {
            out.append(waypoint.x()).append(',')
                    .append(waypoint.y()).append(',')
                    .append(waypoint.z()).append(',')
                    .append(waypoint.name()).append(',')
                    .append(waypoint.color()).append(';');
        }
        return out.toString();
    }

    private boolean hasHelpfulWarp(BlockKey key) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !config.dianaWarpAssist()) return false;

        double estimateX = key.x() + 0.5;
        double estimateY = key.y() + 0.5;
        double estimateZ = key.z() + 0.5;
        double playerDistance = DistanceUtil.euclidean(
                player.getX(), player.getY(), player.getZ(),
                estimateX, estimateY, estimateZ);

        for (DianaWarp warp : DianaWarp.values()) {
            if (!config.dianaWarpEnabled(warp)) continue;

            double warpDistance = DistanceUtil.euclidean(
                    warp.x(), warp.y(), warp.z(),
                    estimateX, estimateY, estimateZ);
            if (playerDistance - warpDistance > config.dianaWarpMinSavings()) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldRenderDianaType(DianaBurrowType type) {
        if (type == DianaBurrowType.START && !config.dianaShowStartBurrows()) return false;
        if (type == DianaBurrowType.MOB && !config.dianaShowMobBurrows()) return false;
        if (type == DianaBurrowType.TREASURE && !config.dianaShowTreasureBurrows()) return false;
        return type != DianaBurrowType.START
                || !config.dianaHideStartBurrowsUntilChainComplete()
                || currentChainComplete;
    }

    private boolean updateChainState(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean previous = currentChainComplete;

        if (lower.contains("finished the griffin burrow chain")) {
            currentChainComplete = true;
            return previous != currentChainComplete;
        }

        if (!lower.contains("griffin burrow")) return false;

        Matcher matcher = CHAIN_PROGRESS_PATTERN.matcher(text);
        if (!matcher.find()) return false;

        int current = parsePositiveInt(matcher.group(1));
        int total = parsePositiveInt(matcher.group(2));
        if (current <= 0 || total <= 0) return false;

        currentChainComplete = current >= total;
        return previous != currentChainComplete;
    }

    private void reset() {
        sightings.clear();
        guesses.clear();
        arrowPoints.clear();
        spadeCurvePoints.clear();
        recentSpadeCurveEstimates.clear();
        recentArrows.clear();
        lastRenderedSignature = "";
        lastBurrowRelatedChatMillis = 0L;
        lastSpadeAttackBlock = null;
        lastSpadeAttackMillis = 0L;
        lastSpadeSearchMillis = 0L;
        spadeEchoParticlesSinceClick = 0;
        reportedSpadeEchoForClick = false;
        reportedArrowParticlesForSequence = false;
        currentChainComplete = true;
        spadeDebugLogTimes.clear();
        if (manager.get(DianaBurrowWaypointGroup.ID) != null) manager.remove(DianaBurrowWaypointGroup.ID);
    }

    private boolean isEnabledInHub() {
        return config.dianaBurrowWaypoints()
                && manager.currentZone() != null
                && HUB_ZONE_ID.equals(manager.currentZone().id());
    }

    private double distanceToPlayerSq(double x, double y, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return Double.MAX_VALUE;
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static BlockKey burrowBlock(ClientboundLevelParticlesPacket packet) {
        return new BlockKey(blockCoord(packet.getX()), blockCoord(packet.getY() - 1.0), blockCoord(packet.getZ()));
    }

    private static int blockCoord(double value) {
        return (int) Math.floor(value);
    }

    private static boolean close(double value, double expected) {
        return Math.abs(value - expected) < 0.0001;
    }

    private static <T> void trimOldest(List<T> values, int maxSize) {
        int over = values.size() - maxSize;
        if (over > 0) values.subList(0, over).clear();
    }

    private static boolean isPlausibleBurrowY(int y) {
        return y >= MIN_BURROW_Y && y <= MAX_BURROW_Y;
    }

    private static boolean isPlausibleHubColumn(BlockKey block) {
        return block.x() >= HUB_MIN_X
                && block.x() <= HUB_MAX_X
                && block.z() >= HUB_MIN_Z
                && block.z() <= HUB_MAX_Z;
    }

    private static boolean isSpadeCurveParticle(ClientboundLevelParticlesPacket packet) {
        return packet.getParticle().getType() == ParticleTypes.DRIPPING_LAVA
                && packet.getCount() == 2
                && close(packet.getMaxSpeed(), -0.5);
    }

    private static boolean isDianaSpade(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        String name = item.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("ancestral spade")
                || name.contains("archaic spade")
                || name.contains("deific spade");
    }

    private boolean hasRecentArrowContext(long now) {
        return now - lastBurrowRelatedChatMillis <= RECENT_BURROW_ARROW_MS
                || now - lastSpadeSearchMillis <= RECENT_SPADE_SEARCH_ARROW_MS;
    }

    private static boolean isBurrowDugMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("you dug out")
                || lower.contains("finished the griffin burrow chain")
                || lower.contains("defeat all the burrow defenders");
    }

    private static boolean isMobDugMessage(String lower) {
        return lower.contains("you dug out")
                && !lower.contains("griffin burrow")
                && !isTreasureDugMessage(lower);
    }

    private static boolean isTreasureDugMessage(String lower) {
        return lower.contains("rare drop")
                || lower.contains("coins")
                || lower.contains("griffin feather")
                || lower.contains("crown of greed")
                || lower.contains("washed-up souvenir")
                || lower.contains("minos relic")
                || lower.contains("chimera");
    }

    private static int parsePositiveInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String format(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    private static String formatDistance(double distance) {
        return String.format(Locale.ROOT, "%.1f", distance);
    }

    private static String formatPoint(Vec3 point) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", point.x(), point.y(), point.z());
    }

    private void debugSpade(String key, long minIntervalMillis, String message) {
        if (!config.dianaSpadeDebugLogging()) return;

        long now = System.currentTimeMillis();
        Long last = spadeDebugLogTimes.get(key);
        if (last != null && now - last < minIntervalMillis) return;

        spadeDebugLogTimes.put(key, now);
        Waypointer.LOGGER.info("[Diana spade] {}", message);
    }

    private static double distanceToRay(Ray ray, Vec3 point) {
        Vec3 originToPoint = point.subtract(ray.origin());
        Vec3 projected = ray.direction().scale(originToPoint.dot(ray.direction()));
        return originToPoint.subtract(projected).length();
    }

    private static final class Sighting {
        private boolean hasEnchant;
        private DianaBurrowType type;
        private DianaBurrowType confirmedType;
        private long lastSeenMillis;
    }

    private record Guess(long createdAtMillis, EstimateSource source) {}

    private enum EstimateSource {
        ARROW,
        SPADE_CURVE
    }

    private record TimedPoint(Vec3 point, long timeMillis) {}

    private record TimedRay(Ray ray, long timeMillis) {}

    private record Candidate(double scaledDistance, double distanceFromOrigin) {}

    private record IntRange(int min, int max) {}

    private record Ray(Vec3 origin, Vec3 direction) {
        boolean sameRay(Ray other) {
            return origin.distanceSquared(other.origin) < 0.01
                    && direction.distanceSquared(other.direction) < 0.0001;
        }
    }

    private record BlockKey(int x, int y, int z) implements Comparable<BlockKey> {
        double distanceSq(BlockKey other) {
            return distanceSq(other.x + 0.5, other.y + 0.5, other.z + 0.5);
        }

        double distance(BlockKey other) {
            return Math.sqrt(distanceSq(other));
        }

        double distanceSq(double ox, double oy, double oz) {
            double dx = x + 0.5 - ox;
            double dy = y + 0.5 - oy;
            double dz = z + 0.5 - oz;
            return dx * dx + dy * dy + dz * dz;
        }

        String format() {
            return DianaBurrowDetector.format(x, y, z);
        }

        @Override
        public int compareTo(BlockKey other) {
            int byX = Integer.compare(x, other.x);
            if (byX != 0) return byX;
            int byY = Integer.compare(y, other.y);
            if (byY != 0) return byY;
            return Integer.compare(z, other.z);
        }
    }

    private record Vec3(double x, double y, double z) {
        Vec3 add(double ox, double oy, double oz) {
            return new Vec3(x + ox, y + oy, z + oz);
        }

        Vec3 add(Vec3 other) {
            return add(other.x, other.y, other.z);
        }

        Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        Vec3 scale(double amount) {
            return new Vec3(x * amount, y * amount, z * amount);
        }

        Vec3 normalize() {
            double length = length();
            return length < EPSILON ? this : scale(1.0 / length);
        }

        Vec3 cross(Vec3 other) {
            return new Vec3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        double dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        double length() {
            return Math.sqrt(lengthSquared());
        }

        double lengthSquared() {
            return x * x + y * y + z * z;
        }

        double distance(Vec3 other) {
            return Math.sqrt(distanceSquared(other));
        }

        double distanceSquared(Vec3 other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
