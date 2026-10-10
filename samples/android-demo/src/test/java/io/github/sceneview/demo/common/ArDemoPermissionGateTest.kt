package io.github.sceneview.demo.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ArDemoPermissionGateTest {

    @Test
    fun `granted permission shows the AR demo`() {
        assertEquals(
            ArDemoPermissionUiState.ShowDemo,
            arDemoPermissionUiState(granted = true, requested = false, shouldShowRationale = false),
        )
    }

    @Test
    fun `a first visit without the permission asks for it`() {
        assertEquals(
            ArDemoPermissionUiState.RequestPermission,
            arDemoPermissionUiState(granted = false, requested = false, shouldShowRationale = false),
        )
    }

    @Test
    fun `first denial that can ask again shows retry`() {
        assertEquals(
            ArDemoPermissionUiState.RetryPermission,
            arDemoPermissionUiState(granted = false, requested = true, shouldShowRationale = true),
        )
    }

    @Test
    fun `permanent denial opens system settings`() {
        assertEquals(
            ArDemoPermissionUiState.OpenSettings,
            arDemoPermissionUiState(granted = false, requested = true, shouldShowRationale = false),
        )
    }

    /** #4139 (c): back from settings with the camera on, no "Try again" tap in between. */
    @Test
    fun `grant after a permanent denial shows the demo without another tap`() {
        assertEquals(
            ArDemoPermissionUiState.ShowDemo,
            arDemoPermissionUiState(granted = true, requested = true, shouldShowRationale = false),
        )
    }
}
