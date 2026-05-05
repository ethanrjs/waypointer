package dev.ethan.waypointer.api;

import dev.ethan.waypointer.Waypointer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Discovers and invokes third-party Waypointer API entrypoints.
 */
public final class WaypointerApiEntrypoints {

    public static final String ENTRYPOINT_KEY = "waypointer:api";

    private WaypointerApiEntrypoints() {
    }

    public static int invokeFabricEntrypoints(WaypointerApi api) {
        Objects.requireNonNull(api, "api");
        List<NamedEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, WaypointerApiEntrypoint.class)
                .stream()
                .map(WaypointerApiEntrypoints::named)
                .toList();
        return invokeEntrypoints(entrypoints, api, WaypointerApiEntrypoints::logFailure);
    }

    static int invokeEntrypoints(
            Collection<NamedEntrypoint> entrypoints,
            WaypointerApi api,
            BiConsumer<String, Throwable> onFailure) {
        int invoked = 0;
        for (NamedEntrypoint entrypoint : entrypoints) {
            try {
                entrypoint.entrypoint().onWaypointerApiReady(api);
                invoked++;
            } catch (Throwable error) {
                onFailure.accept(entrypoint.modId(), error);
            }
        }
        return invoked;
    }

    static NamedEntrypoint named(String modId, WaypointerApiEntrypoint entrypoint) {
        return new NamedEntrypoint(modId, entrypoint);
    }

    private static NamedEntrypoint named(EntrypointContainer<WaypointerApiEntrypoint> container) {
        String modId = container.getProvider().getMetadata().getId();
        return named(modId, container.getEntrypoint());
    }

    private static void logFailure(String modId, Throwable error) {
        Waypointer.LOGGER.error("Waypointer API entrypoint from {} failed", modId, error);
    }

    record NamedEntrypoint(String modId, WaypointerApiEntrypoint entrypoint) {
        NamedEntrypoint {
            modId = modId == null || modId.isBlank() ? "unknown" : modId;
            entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
        }
    }
}
