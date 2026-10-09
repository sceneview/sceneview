package io.github.sceneview.demo.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ArDemoPermissionGateTest {

    @Test
    fun `granted permission shows the AR demo`() {
        assertEquals(
            ArDemoPermissionUiState.ShowDemo,
            arDemoPermissionUiState(
                permission = ArCameraPermissionState.Granted,
                shouldShowRationale = false,
                session = ArDemoSessionState.Running,
            ),
        )
    }

    @Test
    fun `first denial that can ask again shows retry`() {
        assertEquals(
            ArDemoPermissionUiState.RetryPermission,
            arDemoPermissionUiState(
                permission = ArCameraPermissionState.Denied,
                shouldShowRationale = true,
                session = ArDemoSessionState.BlockedByPermission,
            ),
        )
    }

    @Test
    fun `permanent denial opens system settings`() {
        assertEquals(
            ArDemoPermissionUiState.OpenSettings,
            arDemoPermissionUiState(
                permission = ArCameraPermissionState.Denied,
                shouldShowRationale = false,
                session = ArDemoSessionState.BlockedByPermission,
            ),
        )
    }

    @Test
    fun `grant after settings restarts the AR session`() {
        assertEquals(
            ArDemoPermissionUiState.RetrySession,
            arDemoPermissionUiState(
                permission = ArCameraPermissionState.Granted,
                shouldShowRationale = false,
                session = ArDemoSessionState.BlockedByPermission,
            ),
        )
    }
}
