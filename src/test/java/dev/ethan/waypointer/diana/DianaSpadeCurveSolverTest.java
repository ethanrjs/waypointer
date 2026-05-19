package dev.ethan.waypointer.diana;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DianaSpadeCurveSolverTest {

    @Test
    void extrapolatesLongSpadeCurveToNegativeCoordinateBurrow() {
        var estimate = DianaSpadeCurveSolver.estimate(List.of(
                new DianaSpadeCurveSolver.Sample(-10.301, 76.748, -12.498),
                new DianaSpadeCurveSolver.Sample(-10.604, 76.908, -12.516),
                new DianaSpadeCurveSolver.Sample(-11.085, 77.057, -12.56),
                new DianaSpadeCurveSolver.Sample(-11.736, 77.195, -12.63),
                new DianaSpadeCurveSolver.Sample(-12.552, 77.322, -12.725),
                new DianaSpadeCurveSolver.Sample(-13.525, 77.439, -12.843),
                new DianaSpadeCurveSolver.Sample(-14.65, 77.545, -12.985),
                new DianaSpadeCurveSolver.Sample(-15.918, 77.642, -13.149),
                new DianaSpadeCurveSolver.Sample(-17.325, 77.728, -13.334),
                new DianaSpadeCurveSolver.Sample(-18.862, 77.805, -13.539),
                new DianaSpadeCurveSolver.Sample(-20.524, 77.873, -13.762),
                new DianaSpadeCurveSolver.Sample(-22.303, 77.931, -14.004),
                new DianaSpadeCurveSolver.Sample(-24.194, 77.98, -14.263),
                new DianaSpadeCurveSolver.Sample(-26.189, 78.02, -14.538),
                new DianaSpadeCurveSolver.Sample(-28.282, 78.052, -14.828),
                new DianaSpadeCurveSolver.Sample(-30.466, 78.075, -15.131),
                new DianaSpadeCurveSolver.Sample(-32.734, 78.089, -15.448),
                new DianaSpadeCurveSolver.Sample(-35.081, 78.096, -15.778)));

        assertTrue(estimate.isPresent());
        assertEquals(-117, blockCoord(estimate.get().x()));
        assertEquals(73, blockCoord(estimate.get().y() - 0.5));
        assertEquals(-28, blockCoord(estimate.get().z()));
    }

    @Test
    void handlesShortCurveWhenTheBurrowIsAlreadyClose() {
        var estimate = DianaSpadeCurveSolver.estimate(List.of(
                new DianaSpadeCurveSolver.Sample(-198.549, 89.479, 99.492),
                new DianaSpadeCurveSolver.Sample(-196.918, 90.461, 97.829),
                new DianaSpadeCurveSolver.Sample(-195.75, 90.498, 96.17),
                new DianaSpadeCurveSolver.Sample(-194.993, 89.764, 94.744),
                new DianaSpadeCurveSolver.Sample(-194.595, 88.431, 93.782)));

        assertTrue(estimate.isPresent());
        assertEquals(-195, blockCoord(estimate.get().x()));
        assertEquals(86, blockCoord(estimate.get().y() - 0.5));
        assertEquals(93, blockCoord(estimate.get().z()));
    }

    private static int blockCoord(double value) {
        return (int) Math.floor(value);
    }
}
