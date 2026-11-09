package io.github.sceneview.testing;

import com.google.android.filament.Engine;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Gives an instrumented {@link Engine} non-zero manager handles in JVM tests.
 *
 * <p>Robolectric tests that instrument {@code com.google.android.filament} build an
 * {@code Engine} without its native side: every native method returns zero. Up to Filament 1.72
 * the engine still wrapped those zero handles. From 1.77 its manager getters throw
 * {@code IllegalStateException("Couldn't get TransformManager")} on a zero handle, so this hands
 * them a placeholder one. Nothing ever dereferences it: the managers' own natives stay stubbed.
 *
 * <p>Registered for every test in {@code src/test/resources/robolectric.properties}.
 */
@Implements(Engine.class)
public class ShadowFilamentEngine {

    /** Any non-zero value: the wrappers only check it against zero. */
    private static final long PLACEHOLDER_HANDLE = 1L;

    @Implementation
    protected static long nGetEntityManager(long nativeEngine) {
        return PLACEHOLDER_HANDLE;
    }

    @Implementation
    protected static long nGetRenderableManager(long nativeEngine) {
        return PLACEHOLDER_HANDLE;
    }

    @Implementation
    protected static long nGetLightManager(long nativeEngine) {
        return PLACEHOLDER_HANDLE;
    }

    @Implementation
    protected static long nGetTransformManager(long nativeEngine) {
        return PLACEHOLDER_HANDLE;
    }
}
