import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.screen.preview.RoutePreviewScene;

import java.util.ArrayList;
import java.util.Arrays;

/** Small CPU experiment; excludes route generation, GPU upload, and rendering. */
public final class RoutePreviewSceneBenchmark {
    public static void main(String[] args) {
        WaypointerConfig config = new WaypointerConfig();
        for (int count : new int[]{1_000, 10_000, 30_000}) {
            WaypointGroup group = WaypointGroup.create("Many", "hub");
            group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            ArrayList<Waypoint> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                points.add(Waypoint.at(i, i % 7, i % 13));
            }
            group.addAll(points);
            for (int i = 0; i < 3; i++) RoutePreviewScene.build(group, config, null);

            long[] samples = new long[7];
            int resultCount = 0;
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime();
                resultCount = RoutePreviewScene.build(group, config, null).markers().size();
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            System.out.printf("markers=%d median_ms=%.3f%n", resultCount, samples[3] / 1e6);
        }
    }
}
