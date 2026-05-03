package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.client.renderer.MultiBufferSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Small bridge over Fabric's 1.21.11 -> 26.x world-render event rename.
 *
 * <p>Fabric API 26.1 moved {@code WorldRenderEvents} to
 * {@code LevelRenderEvents} and renamed the context accessors. Keeping this
 * reflection boundary in one place lets the renderers stay source-compatible
 * with the 1.21.11 compile classpath while the 26.x official jar can still
 * register against the newer runtime API.
 */
public final class RenderEventCompat {

    private static final String LEVEL_EVENTS =
            "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents";
    private static final String WORLD_EVENTS =
            "net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents";

    private RenderEventCompat() {}

    public static void registerEndMain(String owner, Consumer<Object> callback) {
        try {
            Class<?> events = findEventsClass();
            Field field = events.getField("END_MAIN");
            Object event = field.get(null);

            Method register = event.getClass().getMethod("register", Object.class);
            Class<?> listenerType = register.getParameterTypes()[0];
            Object listener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[] { listenerType },
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" ->
                                        proxy == (args != null && args.length > 0 ? args[0] : null);
                                case "toString" ->
                                        listenerType.getName()
                                                + "@"
                                                + Integer.toHexString(
                                                        System.identityHashCode(proxy));
                                default -> null;
                            };
                        }
                        if (args != null && args.length > 0) callback.accept(args[0]);
                        return null;
                    });
            register.invoke(event, listener);
        } catch (Throwable t) {
            Waypointer.LOGGER.error("Failed to register {} world render callback", owner, t);
        }
    }

    public static PoseStack poseStack(Object context) {
        Object value = invokeFirst(context, "poseStack", "matrices");
        return value instanceof PoseStack ps ? ps : null;
    }

    public static MultiBufferSource bufferSource(Object context) {
        Object value = invokeFirst(context, "bufferSource", "consumers");
        return value instanceof MultiBufferSource buffers ? buffers : null;
    }

    private static Class<?> findEventsClass() throws ClassNotFoundException {
        try {
            return Class.forName(LEVEL_EVENTS);
        } catch (ClassNotFoundException ignored) {
            return Class.forName(WORLD_EVENTS);
        }
    }

    private static Object invokeFirst(Object target, String first, String second) {
        if (target == null) return null;
        for (String name : new String[] { first, second }) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
