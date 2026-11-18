package io.github.sceneview.ar;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Nonzero Java manager handles; Filament's instrumented native methods remain JVM stubs. */
public final class SessionDependencyShadows {
    @Implements(Engine.class)
    public static class EngineHandles {
        @Implementation protected static long nGetEntityManager(long engine) { return 1L; }
        @Implementation protected static long nGetRenderableManager(long engine) { return 1L; }
        @Implementation protected static long nGetLightManager(long engine) { return 1L; }
        @Implementation protected static long nGetTransformManager(long engine) { return 1L; }
    }

    @Implements(EntityManager.class)
    public static class EntityHandles {
        @Implementation protected static long nGet() { return 1L; }
    }
}
