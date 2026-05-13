package dev.ethan.waypointer.render;

import dev.ethan.waypointer.config.WaypointerConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Detects whether the experimental Iris-safe HUD renderer should replace the
 * normal world-space waypoint geometry for this frame.
 *
 * <p>Iris is optional, so this class talks to its public API reflectively. A
 * direct dependency would make Waypointer require Iris at runtime; reflection
 * lets vanilla/Sodium installs keep using the normal renderer with no extra mod
 * relationship.
 */
final class IrisShaderFallback {

    private static final String IRIS_MOD_ID = "iris";
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

    private static Boolean irisLoaded;
    private static Object irisApi;
    private static Method isShaderPackInUse;
    private static boolean apiUnavailable;

    private IrisShaderFallback() {
    }

    static boolean shouldUse(WaypointerConfig config) {
        return config.irisShaderHudFallback() && isShaderPackInUse();
    }

    private static boolean isShaderPackInUse() {
        if (!isIrisLoaded()) return false;
        if (apiUnavailable) return false;

        try {
            Method method = shaderPackMethod();
            if (method == null) return false;
            return Boolean.TRUE.equals(method.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            apiUnavailable = true;
            return false;
        }
    }

    private static boolean isIrisLoaded() {
        if (irisLoaded == null) {
            irisLoaded = FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID);
        }
        return irisLoaded;
    }

    private static Method shaderPackMethod() throws ReflectiveOperationException {
        if (isShaderPackInUse != null) return isShaderPackInUse;

        Class<?> apiClass = Class.forName(IRIS_API_CLASS);
        Method getInstance = apiClass.getMethod("getInstance");
        irisApi = getInstance.invoke(null);
        isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
        return isShaderPackInUse;
    }
}
