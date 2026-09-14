package com.babbur.waypointer.debug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerTickEstimatorTest {
    private static final long SECOND = 1_000_000_000L;

    @Test void fixedHighPingDoesNotReduceTpsAndSustainedSlowTicksAreMeasured() {
        for (int tps : new int[]{10, 15, 20}) {
            var estimator = new ServerTickEstimator();
            for (int i = 0; i <= 60; i++) {
                long now = i * SECOND + 400_000_000L;
                estimator.recordTime(i * tps, now);
                estimator.recordPing(800, now);
            }
            var snapshot = estimator.snapshot(60 * SECOND + 400_000_000L);
            assertEquals(tps, snapshot.mediumWindow().observedTps(), 0.001);
            assertEquals(tps, snapshot.mediumWindow().trendTps(), 0.001);
            assertEquals(800, snapshot.medianPing());
            assertEquals("Stable", snapshot.quality());
        }
    }

    @Test void variableLatencyBurstsAndClientStallsRemainVisibleWithoutDiscardingServerLag() {
        var estimator = new ServerTickEstimator();
        for (int i = 0; i <= 60; i++) {
            long jitter = i % 2 == 0 ? 0 : 300_000_000L;
            estimator.recordTime(i * 20, i * SECOND + jitter);
            estimator.recordPing(i % 2 == 0 ? 50 : 650, i * SECOND);
        }
        estimator.recordClientTick(59 * SECOND);
        estimator.recordClientTick(60 * SECOND);
        var snapshot = estimator.snapshot(60 * SECOND);
        assertEquals(20, snapshot.mediumWindow().observedTps(), 0.001);
        assertEquals(20, snapshot.mediumWindow().trendTps(), 0.2);
        assertTrue(snapshot.mediumWindow().deliveryJitterMillis() > 250);
        assertTrue(snapshot.quality().startsWith("Uncertain"));
        assertEquals(1, snapshot.clientStalls());
        assertEquals(1000, snapshot.longestClientStallMillis());

        estimator.reset("test pause");
        for (int i = 0; i <= 30; i++) estimator.recordTime(i * 20, i * SECOND);
        // A five-second server stall is retained in observed throughput, not filtered as an outlier.
        for (int i = 31; i <= 55; i++) estimator.recordTime(i * 20, (i + 5) * SECOND);
        snapshot = estimator.snapshot(60 * SECOND);
        assertEquals(1100.0 / 60, snapshot.mediumWindow().observedTps(), 0.001);
        assertEquals(6000, snapshot.longestPacketGapMillis());
        assertEquals("Uncertain", snapshot.quality());
    }

    @Test void bundledClockUpdatesUseLatestTickWithoutZeroDurationIntervals() {
        var estimator = new ServerTickEstimator();
        estimator.recordTime(0, 0);
        estimator.recordTime(10, SECOND);
        estimator.recordTime(20, SECOND);
        assertEquals(2, estimator.snapshot(SECOND).shortWindow().samples());
        assertEquals(20, estimator.snapshot(SECOND).shortWindow().observedTps());
    }

    @Test void isolatedClockJumpAndPingUpdatesCannotClaimStableFreshMeasurements() {
        var estimator = new ServerTickEstimator();
        for (int i = 0; i <= 60; i++) estimator.recordTime(i * 20L + (i >= 30 ? 6000 : 0), i * SECOND);
        estimator.recordPing(200, 0);
        var snapshot = estimator.snapshot(60 * SECOND);
        assertEquals("Uncertain", snapshot.quality());
        assertEquals(60, snapshot.pingAgeSeconds());
        assertEquals(-1, estimator.snapshot(61 * SECOND).latestPing());
        assertTrue(Double.isNaN(estimator.snapshot(61 * SECOND).medianPing()));
    }

    @Test void missingPacketsClockResetsAndConnectionChangesDoNotInventTps() {
        var estimator = new ServerTickEstimator();
        assertTrue(Double.isNaN(estimator.snapshot(0).shortWindow().observedTps()));
        estimator.recordTime(100, 0);
        estimator.recordTime(120, SECOND);
        assertTrue(estimator.snapshot(8 * SECOND).quality().startsWith("Stale"));
        estimator.recordTime(0, 9 * SECOND);
        assertEquals(1, estimator.snapshot(9 * SECOND).discontinuities());
        assertTrue(Double.isNaN(estimator.snapshot(9 * SECOND).shortWindow().observedTps()));
        estimator.recordTime(20, 8 * SECOND); // Out-of-order arrival must not alter the baseline.
        assertEquals(1, estimator.snapshot(9 * SECOND).shortWindow().samples());
        estimator.setTickState(10, true);
        assertTrue(estimator.snapshot(9 * SECOND).frozen());
        estimator.reset("disconnected");
        assertEquals("disconnected", estimator.snapshot(10 * SECOND).resetReason());
        assertEquals(-1, estimator.snapshot(10 * SECOND).latestPing());
        assertFalse(estimator.snapshot(10 * SECOND).frozen());
    }
}
