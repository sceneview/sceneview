package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/** People Occlusion (#4649): the status never claims a person it did not see. */
class PeopleOcclusionStatusTest {

    @Test
    fun `off wins over whatever ARCore sees`() {
        assertEquals(PeopleOcclusionStatus.Off, PeopleOcclusion.status(false, 0.5f))
        assertEquals(PeopleOcclusionStatus.Off, PeopleOcclusion.status(false, null))
    }

    @Test
    fun `no semantic frame yet is starting, not searching`() {
        assertEquals(PeopleOcclusionStatus.Starting, PeopleOcclusion.status(true, null))
    }

    @Test
    fun `speckle below the threshold is nobody in view`() {
        assertEquals(PeopleOcclusionStatus.Searching, PeopleOcclusion.status(true, 0f))
        assertEquals(PeopleOcclusionStatus.Searching, PeopleOcclusion.status(true, 0.004f))
    }

    @Test
    fun `a person covering the threshold is detected`() {
        val threshold = PeopleOcclusion.PERSON_FRACTION_THRESHOLD
        assertEquals(PeopleOcclusionStatus.Detected, PeopleOcclusion.status(true, threshold))
        assertEquals(PeopleOcclusionStatus.Detected, PeopleOcclusion.status(true, 0.3f))
    }
}
