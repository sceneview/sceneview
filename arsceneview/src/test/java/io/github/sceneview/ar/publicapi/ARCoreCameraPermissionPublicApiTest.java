package io.github.sceneview.ar.publicapi;

import com.google.ar.core.ArCoreApk;
import io.github.sceneview.ar.ARCameraPermissionRecovery;
import io.github.sceneview.ar.ARCore;
import io.github.sceneview.ar.ARPermissionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void completeRecoveryReachesDirectHostsIncludingGrantAndSameLegacyValueTransitions() {
        ARCore core = newCore();
        FakeHandler handler = new FakeHandler();
        List<ARCameraPermissionRecovery> recoveries = new ArrayList<>();
        List<Boolean> legacyDenials = new ArrayList<>();
        int[] grants = {0};
        assertNull(core.getCameraPermissionRecovery());
        core.setOnCameraPermissionRecoveryChanged(recovery -> {
            assertEquals(recovery, core.getCameraPermissionRecovery());
            recoveries.add(recovery);
            return Unit.INSTANCE;
        });
        core.setOnCameraPermissionDenied(denial -> {
            legacyDenials.add(denial);
            return Unit.INSTANCE;
        });
        core.setOnCameraPermissionGranted(() -> {
            grants[0]++;
            return Unit.INSTANCE;
        });
        assertTrue(recoveries.isEmpty());

        // A raised rationale flag always permits another request, whatever the timing.
        handler.rationale = true;
        handler.rationaleAfter = true;
        assertFalse(core.checkPermissionAndInstall(handler));
        assertEquals(ARCameraPermissionRecovery.Request, core.getCameraPermissionRecovery());

        // Two unexplained answers must expose both actions, independent of their timing.
        handler.rationale = false;
        handler.rationaleAfter = false;
        core.retryCameraPermission(handler);
        core.retryCameraPermission(handler);
        assertEquals(ARCameraPermissionRecovery.RequestWithSettings, core.getCameraPermissionRecovery());
        assertEquals(Arrays.asList(false, true), legacyDenials);

        // The rationale transition confirms a block. The enum changes; the legacy bit does not.
        handler.rationale = true;
        core.retryCameraPermission(handler);
        assertEquals(ARCameraPermissionRecovery.Settings, core.getCameraPermissionRecovery());
        assertEquals(Arrays.asList(false, true), legacyDenials);
        assertEquals(Arrays.asList(
                ARCameraPermissionRecovery.Request,
                ARCameraPermissionRecovery.RequestWithSettings,
                ARCameraPermissionRecovery.Settings), recoveries);

        // A grant read from settings clears the public recovery and both host signals once.
        core.openAppSettings(handler);
        handler.granted = true;
        assertTrue(core.checkPermissionAndInstall(handler));
        assertTrue(core.checkPermissionAndInstall(handler));
        assertNull(core.getCameraPermissionRecovery());
        assertFalse(core.isCameraPermissionDenied());
        assertEquals(1, handler.settingsOpened);
        assertEquals(1, grants[0]);
        assertEquals(Arrays.asList(
                ARCameraPermissionRecovery.Request,
                ARCameraPermissionRecovery.RequestWithSettings,
                ARCameraPermissionRecovery.Settings,
                null), recoveries);
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
