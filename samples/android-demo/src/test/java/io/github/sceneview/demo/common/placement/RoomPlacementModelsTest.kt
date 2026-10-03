package io.github.sceneview.demo.common.placement

import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlacementModelsTest {
    private fun room(id: String, date: Long) = RerunStoredSession(
        id, "Room $id", date, RerunSessionSource.Recorded, 10f, 3f, 100, 2, 4,
    )

    @Test
    fun `without recordings the demo and invitation remain available`() {
        val rows = roomPlacementModels(emptyList(), "Demo room")
        assertEquals(1, rows.size)
        assertEquals(BUNDLED_ROOM_RECORDING_ID, rows.single().roomRecordingId)
        assertTrue(rows.single().inviteRoomScan)
    }

    @Test
    fun `one recording precedes the bundled demo`() {
        val rows = roomPlacementModels(listOf(room("a", 100)), "Demo room")
        assertEquals(listOf("a", BUNDLED_ROOM_RECORDING_ID), rows.map { it.roomRecordingId })
        assertEquals("Room a", rows.first().displayName)
        assertFalse(rows.any { it.inviteRoomScan })
    }

    @Test
    fun `many recordings are newest first with stable unique ids`() {
        val rows = roomPlacementModels(listOf(room("a", 100), room("b", 300), room("c", 200), room("a", 100)), "Demo room")
        assertEquals(listOf("b", "c", "a", BUNDLED_ROOM_RECORDING_ID), rows.map { it.roomRecordingId })
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        assertFalse(rows.any { it.inviteRoomScan })
    }
}
