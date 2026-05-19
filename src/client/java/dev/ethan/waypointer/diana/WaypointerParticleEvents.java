package dev.ethan.waypointer.diana;

import dev.ethan.waypointer.Waypointer;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WaypointerParticleEvents {

    public interface Listener {
        void onParticle(ClientboundLevelParticlesPacket packet);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private WaypointerParticleEvents() {}

    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void emit(ClientboundLevelParticlesPacket packet) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onParticle(packet);
            } catch (RuntimeException e) {
                Waypointer.LOGGER.warn("Waypointer particle listener failed; skipping this particle packet", e);
            }
        }
    }
}
