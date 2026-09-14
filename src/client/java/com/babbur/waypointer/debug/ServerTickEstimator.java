package com.babbur.waypointer.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Passive server-clock estimates. Arrival timing cannot prove server processing time. */
public final class ServerTickEstimator {
    private static final long SECOND = 1_000_000_000L;
    private static final int CAPACITY = 256;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ArrayDeque<Ping> pings = new ArrayDeque<>();
    private final ArrayDeque<Stall> stalls = new ArrayDeque<>();
    private long lastClientTick = Long.MIN_VALUE;
    private int discontinuities;
    private String resetReason = "not connected";
    private float targetTps = 20;
    private boolean frozen;

    private record Sample(long ticks, long at) { }
    private record Ping(int millis, long at) { }
    private record Stall(double millis, long at) { }

    public record Window(int samples, double seconds, double observedTps,
                         double trendTps, double deliveryJitterMillis) { }
    public record Snapshot(Window shortWindow, Window mediumWindow, Window longWindow,
                           int latestPing, double medianPing, double p95Ping, double pingJitter,
                           double pingAgeSeconds, double packetAgeSeconds, double longestPacketGapMillis,
                           int clientStalls, double longestClientStallMillis, String quality,
                           int discontinuities, String resetReason, float targetTps, boolean frozen) { }

    public synchronized void reset(String reason) {
        samples.clear();
        pings.clear();
        stalls.clear();
        lastClientTick = Long.MIN_VALUE;
        discontinuities = 0;
        resetReason = reason;
        targetTps = 20;
        frozen = false;
    }

    public synchronized void setTickState(float target, boolean isFrozen) {
        if (Float.isFinite(target) && target > 0) targetTps = target;
        frozen = isFrozen;
    }

    public synchronized void recordTime(long ticks, long now) {
        if (ticks < 0) return;
        Sample previous = samples.peekLast();
        if (previous != null) {
            if (now < previous.at) return;
            if (now == previous.at) {
                if (ticks >= previous.ticks) {
                    samples.removeLast();
                    samples.addLast(new Sample(ticks, now));
                }
                return;
            }
            // Clock rewrites are not TPS; keep normal lag gaps and catch-up bursts in the window.
            if (ticks < previous.ticks || ticks - previous.ticks > 12_000
                    || now - previous.at > 180 * SECOND) {
                samples.clear();
                discontinuities++;
                resetReason = "server clock discontinuity or long observation gap";
            }
        }
        samples.addLast(new Sample(ticks, now));
        while (samples.size() > CAPACITY || now - samples.getFirst().at > 180 * SECOND) samples.removeFirst();
    }

    public synchronized void recordPing(int millis, long now) {
        if (millis < 0 || millis > 60_000) return;
        if (!pings.isEmpty() && now <= pings.getLast().at) return;
        pings.addLast(new Ping(millis, now));
        while (pings.size() > CAPACITY || now - pings.getFirst().at > 60 * SECOND) pings.removeFirst();
    }

    public synchronized void recordClientTick(long now) {
        if (lastClientTick != Long.MIN_VALUE && now > lastClientTick) {
            double gap = (now - lastClientTick) / 1_000_000.0;
            if (gap >= 250) stalls.addLast(new Stall(gap, now));
        }
        lastClientTick = now;
        while (!stalls.isEmpty() && (stalls.size() > CAPACITY || now - stalls.getFirst().at > 60 * SECOND)) {
            stalls.removeFirst();
        }
    }

    public synchronized Snapshot snapshot(long now) {
        Window shortWindow = window(now, 10);
        Window medium = window(now, 60);
        Window longWindow = window(now, 180);
        List<Ping> recent = pings.stream().filter(p -> now - p.at <= 60 * SECOND).toList();
        double[] pingValues = recent.stream().mapToDouble(Ping::millis).sorted().toArray();
        double pingMedian = percentile(pingValues, 0.5);
        double pingJitter = percentile(pingValues, 0.95) - percentile(pingValues, 0.05);
        double packetAge = samples.isEmpty() ? Double.NaN : (now - samples.getLast().at) / (double) SECOND;
        double pingAge = pings.isEmpty() ? Double.NaN : (now - pings.getLast().at) / (double) SECOND;
        double longestGap = 0;
        List<Double> gaps = new ArrayList<>();
        Sample previous = null;
        for (Sample sample : samples) {
            if (previous != null && now - sample.at <= 60 * SECOND) {
                double gap = (sample.at - previous.at) / 1_000_000.0;
                longestGap = Math.max(longestGap, gap);
                gaps.add(gap);
            }
            previous = sample;
        }
        int stallCount = 0;
        double longestStall = 0;
        for (Stall stall : stalls) if (now - stall.at <= 60 * SECOND) {
            stallCount++;
            longestStall = Math.max(longestStall, stall.millis);
        }
        double typicalGap = percentile(gaps.stream().mapToDouble(Double::doubleValue).sorted().toArray(), 0.5);
        boolean deliveryGap = longestGap > Math.max(typicalGap * 2, typicalGap + 500);
        boolean clockAnomaly = medium.observedTps > targetTps * 1.1
                || Math.abs(medium.observedTps - medium.trendTps) > Math.max(1, targetTps * 0.1);
        String quality;
        if (samples.isEmpty()) quality = "Unavailable";
        else if (packetAge > 5) quality = "Stale";
        else if (frozen) quality = "Frozen";
        else if (medium.samples < 5 || medium.seconds < 5) quality = "Warming up";
        else if (medium.deliveryJitterMillis > 150 || pingJitter > 100 || deliveryGap || clockAnomaly || stallCount > 0) {
            quality = "Uncertain";
        } else if (medium.seconds < 20) quality = "Provisional";
        else quality = "Stable";
        return new Snapshot(shortWindow, medium, longWindow,
                recent.isEmpty() ? -1 : recent.getLast().millis,
                pingMedian, percentile(pingValues, 0.95), pingJitter, pingAge, packetAge,
                longestGap, stallCount, longestStall, quality, discontinuities, resetReason, targetTps, frozen);
    }

    private Window window(long now, int seconds) {
        List<Sample> points = samples.stream().filter(s -> now - s.at <= seconds * SECOND).toList();
        if (points.size() < 2) return new Window(points.size(), 0, Double.NaN, Double.NaN, Double.NaN);
        Sample first = points.getFirst(), last = points.getLast();
        double elapsed = (last.at - first.at) / (double) SECOND;
        double observed = (last.ticks - first.ticks) / elapsed;
        // Long-span median slopes reduce endpoint jitter without discarding slow samples from observed TPS.
        // ponytail: bounded 256-sample pairwise fit on debug capture; streaming fit if used in a per-frame HUD.
        ArrayList<Double> slopes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            Sample a = points.get(i), b = points.get(j);
            double span = (b.at - a.at) / (double) SECOND;
            if (span >= Math.max(2, elapsed / 2)) slopes.add((b.ticks - a.ticks) / span);
        }
        double trend = slopes.isEmpty() ? observed : percentile(slopes.stream().mapToDouble(Double::doubleValue).sorted().toArray(), 0.5);
        double[] residuals = new double[points.size() - 1];
        if (trend > 0) for (int i = 1; i < points.size(); i++) {
            Sample a = points.get(i - 1), b = points.get(i);
            residuals[i - 1] = Math.abs((b.at - a.at) / 1_000_000.0 - (b.ticks - a.ticks) * 1000.0 / trend);
        }
        else Arrays.fill(residuals, Double.NaN);
        Arrays.sort(residuals);
        return new Window(points.size(), elapsed, observed, trend, percentile(residuals, 0.95));
    }

    private static double percentile(double[] sorted, double quantile) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[(int) Math.round((sorted.length - 1) * quantile)];
    }
}
