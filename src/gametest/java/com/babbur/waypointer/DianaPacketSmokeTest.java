package com.babbur.waypointer;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.diana.DianaController;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

/** Exercises the real packet listener with synthetic Diana signatures, without a server accuracy claim. */
final class DianaPacketSmokeTest {
    static void run(Minecraft client) {
        java.lang.reflect.Field installed = null;
        Object previous = null;
        try {
            var manager = new ActiveGroupManager();
            manager.onZoneChanged(new Zone("hub", "Hub"));
            var controller = new DianaController(manager, () -> true);
            installed = DianaController.class.getDeclaredField("installed");
            installed.setAccessible(true);
            previous = installed.get(null);
            installed.set(null, controller);
            var show = DianaController.class.getDeclaredMethod("show");
            show.setAccessible(true);
            var packet = new ClientboundLevelParticlesPacket(ParticleTypes.ENCHANTED_HIT, false, false,
                    -20.5, 71, -30.5, .5F, .1F, .5F, .01F, 4);
            client.getConnection().handleParticleEvent(packet);
            show.invoke(controller);
            var groups = manager.activeGroups();
            if (groups.size() != 1 || !groups.getFirst().id().equals(DianaController.GROUP_ID)
                    || groups.getFirst().loadMode() != WaypointGroup.LoadMode.STATIC
                    || !groups.getFirst().temp() || groups.getFirst().size() != 1
                    || groups.getFirst().get(0).x() != -21 || groups.getFirst().get(0).y() != 70
                    || groups.getFirst().get(0).z() != -31) {
                throw new AssertionError("Diana particle packet did not produce its exact temporary burrow marker");
            }
            client.getConnection().handleParticleEvent(new ClientboundLevelParticlesPacket(
                    ParticleTypes.LARGE_SMOKE, false, false, -20.5, 71, -30.5, 0, 0, 0, .01F, 1));
            show.invoke(controller);
            if (!manager.activeGroups().isEmpty()) throw new AssertionError("Dug Diana marker survived smoke packet");
            manager.onZoneChanged(new Zone("private_world", "Private world"));
            client.getConnection().handleParticleEvent(packet);
            show.invoke(controller);
            if (!manager.activeGroups().isEmpty()) throw new AssertionError("Diana detected burrows outside Hub");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Diana packet smoke check failed", e);
        } finally {
            try { if (installed != null) installed.set(null, previous); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
    }
}
