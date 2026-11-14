package io.github.sceneview.ar.publicapi;

import com.google.ar.core.ArCoreApk;
import io.github.sceneview.ar.ARCameraPermissionRecovery;
import io.github.sceneview.ar.ARCore;
import io.github.sceneview.ar.ARCameraPermissionState;
import io.github.sceneview.ar.ARPermissionHandler;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A direct ARCore host, compiled from Java without Kotlin's internal test access. */
public class ARCoreCameraPermissionPublicApiTest {
    @Test
    public void legacyHostRetainsSettingsRecoveryForSuppressedRequests() {
        ARCore core = newCore();
        FakeHandler handler = new FakeHandler();
        List<Boolean> denials = new ArrayList<>();
        core.setOnCameraPermissionDenied(settingsAvailable -> {
            denials.add(settingsAvailable);
            if (settingsAvailable) core.openAppSettings(handler);
            return Unit.INSTANCE;
        });

        assertFalse(core.checkPermissionAndInstall(handler));
        // A second answer must offer settings even if the first one was delayed.
        core.retryCameraPermission(handler);

        assertEquals(Boolean.TRUE, denials.get(denials.size() - 1));
        assertEquals(1, handler.settingsOpened);
    }

    @Test
    public void sceneHostsKeepTheLegacySettingsRecommendationForAmbiguousRecovery() {
        int[] requests = {0};
        int[] settings = {0};
        for (ARCameraPermissionRecovery recovery : ARCameraPermissionRecovery.values()) {
            ARCameraPermissionState state = new ARCameraPermissionState(
                    recovery,
                    () -> { requests[0]++; return Unit.INSTANCE; },
                    () -> { settings[0]++; return Unit.INSTANCE; });
            assertEquals(recovery, state.getRecovery());
            assertEquals(recovery != ARCameraPermissionRecovery.Request, state.getPermanentlyDenied());
            // The documented host pattern, also used by TwoARScenesActivity.
            if (state.getPermanentlyDenied()) state.getOpenSettings().invoke();
            else state.getRequest().invoke();
        }
        assertEquals(1, requests[0]);
        assertEquals(2, settings[0]);
    }

    @Test
    public void originalStateConstructorAndDenialGrantCallbacksRemainUsableFromJava() {
        assertEquals(ARCameraPermissionRecovery.Request,
                new ARCameraPermissionState(false, () -> Unit.INSTANCE, () -> Unit.INSTANCE).getRecovery());
        assertEquals(ARCameraPermissionRecovery.Settings,
                new ARCameraPermissionState(true, () -> Unit.INSTANCE, () -> Unit.INSTANCE).getRecovery());
        ARCore core = newCore();
        FakeHandler handler = new FakeHandler();
        int[] grants = {0};
        core.setOnCameraPermissionGranted(() -> { grants[0]++; return Unit.INSTANCE; });
        assertFalse(core.checkPermissionAndInstall(handler));
        handler.granted = true;
        assertTrue(core.checkPermissionAndInstall(handler));
        assertTrue(core.checkPermissionAndInstall(handler));
        assertFalse(core.isCameraPermissionDenied());
        assertEquals(1, grants[0]);
    }

    private static ARCore newCore() {
        return new ARCore(
                session -> Unit.INSTANCE,
                session -> Unit.INSTANCE,
                session -> Unit.INSTANCE,
                exception -> Unit.INSTANCE,
                (session, config) -> Unit.INSTANCE);
    }

    private static final class FakeHandler implements ARPermissionHandler {
        boolean granted;
        boolean rationale;
        boolean rationaleAfter;
        int settingsOpened;

        @Override public boolean hasCameraPermission() { return granted; }

        @Override public void requestCameraPermission(Function1<? super Boolean, Unit> onResult) {
            rationale = rationaleAfter;
            onResult.invoke(false);
        }

        // This interface is historically inverted: true means no rationale flag.
        @Override public boolean shouldShowPermissionRationale() { return !rationale; }

        @Override public void openAppSettings() { settingsOpened++; }

        @Override public ArCoreApk.Availability checkARCoreAvailability() {
            return ArCoreApk.Availability.SUPPORTED_INSTALLED;
        }

        @Override public boolean requestARCoreInstall(boolean userRequestedInstall) { return false; }
    }
}
