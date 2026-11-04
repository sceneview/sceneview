package io.github.sceneview.testing;

import com.google.android.filament.EntityManager;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Keeps {@link EntityManager#get()} non-null in JVM tests that instrument Filament.
 *
 * <p>Filament 1.77's {@code EntityManager.get()} returns {@code null} when the native singleton
 * handle is zero, which is every native call under Robolectric instrumentation. This returns a
 * placeholder handle instead, so {@code EntityManager.get().create()} answers entity 0 as it did
 * up to 1.72.
 *
 * <p>Registered for every test in {@code src/test/resources/robolectric.properties}.
 */
@Implements(EntityManager.class)
public class ShadowFilamentEntityManager {

    @Implementation
    protected static long nGet() {
        return 1L;
    }
}
