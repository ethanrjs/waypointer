package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointCodecV10EntropyTest {

    @Test
    void autoCanSelectHeaderlessKindTwoEntropyInsideKindZero() throws Exception {
        int[][] coordinates = {
                {863, 70, 564}, {865, 71, 568}, {867, 72, 570},
                {865, 72, 572}, {869, 71, 571}, {871, 71, 573},
                {869, 71, 575}, {869, 71, 577}, {866, 72, 578},
                {869, 72, 575}, {872, 72, 574}, {873, 72, 571}
        };
        WaypointGroup route = WaypointGroup.create("x", "custom");
        for (int index = 0; index < coordinates.length; index++) {
            int[] point = coordinates[index];
            route.add(Waypoint.at(point[0], point[1], point[2]));
        }

        byte[] semantic = WaypointCodec.encodeV10GeneralSemantic(
                List.of(route), WaypointCodec.Options.FULL_FIDELITY,
                WaypointCodec.PackingMode.AUTO);
        V10Transport.Outbound candidate = V10GeneralRouteCodec.selectCandidate(semantic);
        DecodeDebug debug = WaypointCodec.debugDecode("WP:" + candidate.transport());

        assertEquals(7, debug.groups().getFirst().coordModeOrdinal());
        WaypointGroup decoded = WaypointCodec.decodeV10GeneralSemantic(semantic)
                .groups().getFirst();
        for (int index = 0; index < coordinates.length; index++) {
            assertEquals(route.get(index).x(), decoded.get(index).x());
            assertEquals(route.get(index).y(), decoded.get(index).y());
            assertEquals(route.get(index).z(), decoded.get(index).z());
        }
    }
}
