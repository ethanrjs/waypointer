package com.babbur.waypointer.catalog;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Opt-in publish CPU-stage benchmark; values are evidence, never CI thresholds. */
class CatalogPublishPreparationTimingTest {

    @Test
    @EnabledIfSystemProperty(named = "catalog.publish.timing", matches = "true")
    void compareStrictRoundTripAgainstOwnEncodedManifest() {
        WaypointGroup route = largeRoute(8_000);
        PublisherIdentity identity = PublisherIdentity.generate(Instant.EPOCH);
        for (int warmup = 0; warmup < 5; warmup++) {
            prepareStrict(route, identity);
            prepareTrusted(route, identity);
        }

        long[] strict = new long[20];
        long[] trusted = new long[20];
        for (int sample = 0; sample < strict.length; sample++) {
            if ((sample & 1) == 0) {
                strict[sample] = time(() -> prepareStrict(route, identity));
                trusted[sample] = time(() -> prepareTrusted(route, identity));
            } else {
                trusted[sample] = time(() -> prepareTrusted(route, identity));
                strict[sample] = time(() -> prepareStrict(route, identity));
            }
        }

        double strictMedian = medianMillis(strict);
        double trustedMedian = medianMillis(trusted);
        System.out.printf(
                "Catalog publish preparation points=%d strict=%.3fms trusted=%.3fms speedup=%.2fx%n",
                route.size(), strictMedian, trustedMedian, strictMedian / trustedMedian);
    }

    private static void prepareStrict(
            WaypointGroup route, PublisherIdentity identity) {
        String payload = WaypointCodec.encodeCatalog(List.of(route));
        CatalogProtocol.validatePublishRequest(request(payload), identity);
    }

    private static void prepareTrusted(
            WaypointGroup route, PublisherIdentity identity) {
        CatalogProtocol.PreparedCatalogPayload prepared =
                CatalogProtocol.prepareCatalogPayload(List.of(route));
        CatalogProtocol.validatePreparedPublishRequest(
                request(prepared.payload()), identity, prepared);
    }

    private static CatalogPublishRequest request(String payload) {
        return new CatalogPublishRequest(
                payload, "Large route", "Publish preparation benchmark",
                CatalogPublishRequest.Visibility.UNLISTED, "hub", "BenchmarkUser");
    }

    private static WaypointGroup largeRoute(int size) {
        WaypointGroup route = WaypointGroup.create("Large route", "hub");
        int x = 100;
        int y = 64;
        int z = -200;
        for (int index = 0; index < size; index++) {
            x += (index % 7) - 3;
            y = 64 + (index % 19);
            z += (index % 5) - 2;
            route.add(new Waypoint(
                    x, y, z, "Point " + index,
                    (index * 0x45D9F3) & 0xFFFFFF, index & 3, 0.0));
        }
        return route;
    }

    private static long time(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return System.nanoTime() - start;
    }

    private static double medianMillis(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1_000_000.0;
    }
}
