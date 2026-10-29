package io.github.sceneview.demo.ui

import com.google.ar.core.ArCoreApk.Availability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #4461: the AR launcher is drawn again on Back with the verdict it already had. */
class ArCoreAvailabilityMemoryTest {

    @Before
    fun setUp() = ArCoreAvailabilityMemory.reset()

    @After
    fun tearDown() = ArCoreAvailabilityMemory.reset()

    @Test
    fun aNewProcessHasNoVerdict() {
        assertNull(ArCoreAvailabilityMemory.lastVerdict)
    }

    @Test
    fun anAnswerIsKeptForTheNextLauncher() {
        ArCoreAvailabilityMemory.keep(Availability.SUPPORTED_INSTALLED)
        assertEquals(Availability.SUPPORTED_INSTALLED, ArCoreAvailabilityMemory.lastVerdict)
    }

    @Test
    fun stillCheckingNeverReplacesAnAnswer() {
        ArCoreAvailabilityMemory.keep(Availability.SUPPORTED_NOT_INSTALLED)
        ArCoreAvailabilityMemory.keep(Availability.UNKNOWN_CHECKING)
        assertEquals(Availability.SUPPORTED_NOT_INSTALLED, ArCoreAvailabilityMemory.lastVerdict)
    }

    @Test
    fun aLaterAnswerReplacesTheOldOne() {
        ArCoreAvailabilityMemory.keep(Availability.SUPPORTED_NOT_INSTALLED)
        ArCoreAvailabilityMemory.keep(Availability.SUPPORTED_INSTALLED)
        assertEquals(Availability.SUPPORTED_INSTALLED, ArCoreAvailabilityMemory.lastVerdict)
    }

    @Test
    fun onlyAnAnswerDefersTheRecheck() {
        assertFalse(ArCoreAvailabilityMemory.isSettled(null))
        assertFalse(ArCoreAvailabilityMemory.isSettled(Availability.UNKNOWN_CHECKING))
        assertTrue(ArCoreAvailabilityMemory.isSettled(Availability.SUPPORTED_INSTALLED))
        assertTrue(ArCoreAvailabilityMemory.isSettled(Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE))
    }

    @Test
    fun theRecheckWaitsForTheNavigationTransition() {
        assertEquals(350L, ArCoreAvailabilityMemory.RECHECK_DELAY_MILLIS)
    }
}
