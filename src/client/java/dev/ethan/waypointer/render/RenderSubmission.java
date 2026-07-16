package dev.ethan.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Bridges Fabric's immediate 26.1 renderer and deferred 26.2 submit nodes. */
public final class RenderSubmission {

    @FunctionalInterface
    public interface Geometry {
        void emit(VertexConsumer consumer, PoseStack poseStack);
    }

    private RenderSubmission() {}

    static boolean requiredBindingsAvailable() {
        if (optionalPublicMethod(LevelRenderContext.class, "bufferSource") != null) {
            return true;
        }
        try {
            Class<?> collectorType = Class.forName(
                    "net.minecraft.client.renderer.SubmitNodeCollector");
            Class<?> rendererType = Class.forName(
                    "net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer");
            return optionalPublicMethod(LevelRenderContext.class, "submitNodeCollector") != null
                    && optionalPublicMethod(collectorType, "submitCustomGeometry",
                            PoseStack.class, RenderType.class, rendererType) != null;
        } catch (ClassNotFoundException error) {
            return false;
        }
    }

    public static boolean submit(LevelRenderContext context, PoseStack poseStack,
                                 RenderType renderType, Geometry geometry) {
        if (context == null || poseStack == null || renderType == null || geometry == null) {
            return false;
        }

        Object buffers = invokeNoArgs(context, "bufferSource");
        if (buffers != null) {
            return submitImmediate(buffers, poseStack, renderType, geometry);
        }

        Object collector = invokeNoArgs(context, "submitNodeCollector");
        if (collector != null) {
            return submitDeferred(collector, poseStack, renderType, geometry);
        }
        throw new IllegalStateException("No supported Fabric world-render submission API is available");
    }

    private static boolean submitImmediate(Object buffers, PoseStack poseStack,
                                           RenderType renderType, Geometry geometry) {
        try {
            Method getBuffer = publicMethod(buffers.getClass(), "getBuffer", RenderType.class);
            VertexConsumer consumer = (VertexConsumer) getBuffer.invoke(buffers, renderType);
            geometry.emit(consumer, poseStack);

            Method endBatch = optionalPublicMethod(buffers.getClass(), "endBatch", RenderType.class);
            if (endBatch != null) endBatch.invoke(buffers, renderType);
            return true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not submit immediate render geometry", error);
        }
    }

    private static boolean submitDeferred(Object collector, PoseStack poseStack,
                                          RenderType renderType, Geometry geometry) {
        try {
            // ponytail: 26.1 and 26.2 expose binary-incompatible render submission
            // APIs. Keep this reflection bridge until 26.1 support can be dropped.
            ClassLoader loader = collector.getClass().getClassLoader();
            Class<?> collectorType = Class.forName(
                    "net.minecraft.client.renderer.SubmitNodeCollector", false, loader);
            Class<?> rendererType = Class.forName(
                    "net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer",
                    false, loader);
            Object renderer = Proxy.newProxyInstance(loader, new Class<?>[]{rendererType},
                    geometryHandler(geometry));
            Method submit = collectorType.getMethod(
                    "submitCustomGeometry", PoseStack.class, RenderType.class, rendererType);
            submit.invoke(collector, poseStack, renderType, renderer);
            return true;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not submit deferred render geometry", error);
        }
    }

    private static InvocationHandler geometryHandler(Geometry geometry) {
        return (proxy, method, args) -> {
            if ("render".equals(method.getName()) && args != null && args.length == 2) {
                PoseStack poseStack = new PoseStack();
                poseStack.last().set((PoseStack.Pose) args[0]);
                geometry.emit((VertexConsumer) args[1], poseStack);
                return null;
            }
            return switch (method.getName()) {
                case "toString" -> "Waypointer custom geometry";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> null;
            };
        };
    }

    private static Object invokeNoArgs(Object target, String name) {
        try {
            return publicMethod(target.getClass(), name).invoke(target);
        } catch (NoSuchMethodException error) {
            return null;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not invoke render context method " + name, error);
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameterTypes);
        method.trySetAccessible();
        return method;
    }

    private static Method optionalPublicMethod(Class<?> type, String name,
                                               Class<?>... parameterTypes) {
        try {
            return publicMethod(type, name, parameterTypes);
        } catch (NoSuchMethodException error) {
            return null;
        }
    }
}
