package nl.chaos.vanillashader.compat;

import java.lang.reflect.Method;
import net.neoforged.fml.ModList;

/** Turns our effects off while an Iris shader pack is active, so the two never stack. */
public final class IrisCompat {
    private static Object api;
    private static Method inUse;
    private static boolean resolved;

    private IrisCompat() {
    }

    public static boolean shaderPackInUse() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("iris")) {
                try {
                    Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = apiClass.getMethod("getInstance").invoke(null);
                    inUse = apiClass.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException ignored) {
                    api = null;
                }
            }
        }
        if (api == null) {
            return false;
        }
        try {
            return (boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException e) {
            api = null;
            return false;
        }
    }
}
