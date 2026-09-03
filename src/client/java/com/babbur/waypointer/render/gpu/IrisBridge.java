package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Optional Iris API access without a compile-time Iris dependency. */
public final class IrisBridge {

    private static final String IRIS_MOD_ID = "iris";
    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String PROGRAM_ENUM = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final long STATE_REFRESH_NANOS = 250_000_000L;

    public enum Program {
        BASIC, TEXTURED, BEACON_BEAM, LINES
    }

    private static final IrisBridge INSTANCE = new IrisBridge();

    private final boolean modLoaded;
    private Object api;
    private Method isShaderPackInUse;
    private Method isRenderingShadowPass;
    private Method getMinorApiRevision;
    private Method assignPipeline;
    private Class<?> programEnum;
    private boolean resolved;
    private boolean unavailable;
    private int minorRevision = -1;

    private final Set<RenderPipeline> assigned =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile boolean cachedShaderPackInUse;
    private long lastStateRefresh;

    private IrisBridge() {
        this.modLoaded = FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID);
    }

    public static IrisBridge get() {
        return INSTANCE;
    }

    public boolean isIrisLoaded() {
        return modLoaded;
    }

    public boolean apiFailed() {
        return modLoaded && unavailable;
    }

    public boolean isShaderPackInUse() {
        if (!resolve()) return false;
        long now = System.nanoTime();
        if (lastStateRefresh != 0L && now - lastStateRefresh < STATE_REFRESH_NANOS) {
            return cachedShaderPackInUse;
        }
        cachedShaderPackInUse = invokeBoolean(isShaderPackInUse);
        lastStateRefresh = now;
        return cachedShaderPackInUse;
    }

    public boolean isRenderingShadowPass() {
        return resolve() && invokeBoolean(isRenderingShadowPass);
    }

    public int minorApiRevision() {
        return resolve() ? minorRevision : -1;
    }

    /** Assigns a pipeline once and reports whether Iris accepted it. */
    public boolean assign(RenderPipeline pipeline, Program program) {
        if (pipeline == null || program == null || !resolve() || assignPipeline == null) return false;
        synchronized (assigned) {
            if (assigned.contains(pipeline)) return true;
            try {
                Object programConstant = enumConstant(programEnum, program.name());
                assignPipeline.invoke(api, pipeline, programConstant);
                assigned.add(pipeline);
                return true;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Waypointer.LOGGER.warn("Iris rejected pipeline assignment for {} -> {}",
                        pipeline, program, failure);
                return false;
            }
        }
    }

    private boolean resolve() {
        if (!modLoaded || unavailable) return false;
        if (resolved) return true;
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            api = apiClass.getMethod("getInstance").invoke(null);
            isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
            isRenderingShadowPass = apiClass.getMethod("isRenderingShadowPass");
            getMinorApiRevision = apiClass.getMethod("getMinorApiRevision");
            minorRevision = (Integer) getMinorApiRevision.invoke(api);
            programEnum = Class.forName(PROGRAM_ENUM);
            assignPipeline = findMethod(apiClass, "assignPipeline", RenderPipeline.class, programEnum);
            resolved = true;
            Waypointer.LOGGER.info("Iris API v0.{} detected (assignPipeline={})",
                    minorRevision, assignPipeline != null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unavailable = true;
            Waypointer.LOGGER.warn("Iris is loaded but its API could not be bound; "
                    + "shader-pack integration disabled for this session", failure);
            return false;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Object enumConstant(Class<?> enumClass, String name)
            throws ReflectiveOperationException {
        for (Object constant : enumClass.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(name)) return constant;
        }
        throw new NoSuchFieldException(enumClass.getSimpleName() + "." + name);
    }

    private boolean invokeBoolean(Method method) {
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            Waypointer.LOGGER.warn("Iris API call failed; disabling integration", failure);
            return false;
        }
    }
}
