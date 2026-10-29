package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What 3D Scan scans (#4647): the scope remembered by its key, the preset each scope hands the
 * capture, the whole home's numbering and totals, and the words each scope uses.
 */
class ScanScopeTest {

    @Test
    fun `a scope is remembered by its key, and an unknown key falls back to a room`() {
        ScanScope.entries.forEach { assertEquals(it, ScanScope.fromKey(it.key)) }
        assertEquals(ScanScope.Room, ScanScope.fromKey(null))
        assertEquals(ScanScope.Room, ScanScope.fromKey("garden"))
        assertEquals(ScanScope.Room, ScanScope.DEFAULT)
    }

    @Test
    fun `a room and a home keep the capture 3D Scan always took`() {
        val room = ScanPreset.ROOM
        assertEquals(room, ScanScope.Room.preset)
        assertEquals(room, ScanScope.Home.preset)
        assertEquals(DepthBackProjection.FAR_M, room.farM)
        assertEquals(KeyframeGate.MIN_STEP_M, room.photoStepM)
        assertEquals(KeyframeGate.MIN_TURN_DEGREES, room.photoTurnDegrees)
        assertEquals(KeyframeGate.MAX_PHOTOS, room.maxPhotos)
        assertEquals(RerunTsdf.VOXEL_M, room.modelVoxelM)
    }

    @Test
    fun `an object keeps close depth only, denser photos and a finer model`() {
        val obj = ScanScope.Object.preset
        assertEquals(ScanPreset.OBJECT, obj)
        assertEquals(1.5f, obj.farM)
        assertTrue(obj.farM < ScanPreset.ROOM.farM)
        assertTrue(obj.photoStepM < ScanPreset.ROOM.photoStepM)
        assertTrue(obj.photoTurnDegrees < ScanPreset.ROOM.photoTurnDegrees)
        assertTrue(obj.modelVoxelM < ScanPreset.ROOM.modelVoxelM)
    }

    @Test
    fun `only a home goes room by room, and an object has no floor plan`() {
        assertTrue(ScanScope.Home.roomByRoom)
        assertFalse(ScanScope.Room.roomByRoom)
        assertFalse(ScanScope.Object.roomByRoom)
        assertFalse(ScanScope.Object.hasFloorPlan)
        assertTrue(ScanScope.Room.hasFloorPlan)
        assertTrue(ScanScope.Home.hasFloorPlan)
    }

    @Test
    fun `the object preset leaves out depth past its far limit`() {
        // A frame looking at the room's far wall from 1 m inside it: the wall is 2.8 m away.
        val pose = RerunSyntheticRoom.lookPose(Vec3(0f, 1.3f, 1.0f), 0f, 0f)
        val frame = RerunSyntheticRoom.scene.render(pose)
        val room = DepthBackProjection.project(frame)
        val obj = DepthBackProjection.project(frame, farM = ScanPreset.OBJECT.farM)
        assertTrue("room keeps the far wall: ${room.count}", room.count > 0)
        assertTrue("object keeps less: ${obj.count} of ${room.count}", obj.count < room.count)
    }

    @Test
    fun `a home numbers its rooms in order and measures each one later`() {
        var home = HomeScan()
        assertEquals(1, home.nextNumber)
        home = home.add("a", "Room 1 · Oct 19").add("b", "Room 2 · Oct 19")
        assertEquals(listOf(1, 2), home.rooms.map { it.number })
        assertEquals(3, home.nextNumber)
        assertFalse(home.measured)
        home = home.measured(2, RoomSurvey.empty(SurveyGap.NoDepth))
        assertNull(home.rooms[0].survey)
        assertNotNull(home.rooms[1].survey)
    }

    @Test
    fun `a survey of a home left behind never lands in the next home`() {
        // Home A: Room 1 saved, its survey still running off the main thread.
        var home = HomeScan().add("a1", "Room 1 · first home")
        val first = home.id
        // The user leaves it and starts a new home before that survey ends; its Room 1 is saved.
        home = home.next().add("b1", "Room 1 · second home")
        assertNotEquals(first, home.id)
        // Home A's survey finishes now: the new home's Room 1 is untouched.
        home = home.measuredIn(first, 1, RoomSurvey.empty(SurveyGap.NoDepth))
        assertNull(home.rooms.single().survey)
        assertEquals("b1", home.rooms.single().id)
        // Its own survey still lands.
        home = home.measuredIn(home.id, 1, RoomSurvey.empty(SurveyGap.NoDepth))
        assertNotNull(home.rooms.single().survey)
    }

    @Test
    fun `a home's total is the sum of its rooms, each surveyed on its own`() {
        val living = RoomSurveyor.survey(scan(RerunSyntheticRoom.scene, RerunSyntheticRoom.STOPS))
        val bedroom = RoomSurveyor.survey(scan(RerunSyntheticBedroom.scene, RerunSyntheticBedroom.STOPS))
        assertWithin("living room", 14.4f, living.area?.value)
        assertWithin("bedroom", 8.4f, bedroom.area?.value)
        val home = HomeScan().add(null, "Room 1").add(null, "Room 2").measured(1, living).measured(2, bedroom)
        assertTrue(home.measured)
        val areas = home.areas { "Room $it" }
        assertEquals(listOf("Room 1", "Room 2"), areas.rooms.map { it.name })
        val total = areas.area?.value ?: error("no total")
        assertEquals(living.area!!.value + bedroom.area!!.value, total, 1e-3f)
        assertNotNull(areas.volume)
    }

    @Test
    fun `a home gives no total while one room is not measured`() {
        val living = RoomSurveyor.survey(scan(RerunSyntheticRoom.scene, RerunSyntheticRoom.STOPS))
        val waiting = HomeScan().add(null, "Room 1").add(null, "Room 2").measured(1, living)
        assertNull(waiting.areas { "Room $it" }.area)
        val unmeasurable = waiting.measured(2, RoomSurvey.empty(SurveyGap.NoDepth))
        assertNull(unmeasurable.areas { "Room $it" }.area)
        assertNull(unmeasurable.areas { "Room $it" }.rooms[1].area)
    }

    @Test
    fun `each scope names its scan and coaches in its own words`() {
        assertEquals("Object · Oct 19, 3:12 PM", ScanScopeCopy.sessionTitle(ScanScope.Object, 1, "Oct 19, 3:12 PM"))
        assertEquals("Room · Oct 19, 3:12 PM", ScanScopeCopy.sessionTitle(ScanScope.Room, 1, "Oct 19, 3:12 PM"))
        assertEquals("Room 2 · Oct 19, 3:12 PM", ScanScopeCopy.sessionTitle(ScanScope.Home, 2, "Oct 19, 3:12 PM"))
        val obj = ScanScopeCopy.coaching(ScanScope.Object, ScanCopy.COACH_SCANNING)
        val room = ScanScopeCopy.coaching(ScanScope.Room, ScanCopy.COACH_SCANNING)
        assertEquals(ScanScopeCopy.scanning(ScanScope.Object), obj)
        assertEquals(ScanScopeCopy.scanning(ScanScope.Room), room)
        assertTrue(obj != room)
        assertEquals(
            ScanScopeCopy.nothingNew(ScanScope.Object),
            ScanScopeCopy.coaching(ScanScope.Object, ScanCopy.COACH_NOTHING_NEW),
        )
        // What is not guidance reads the same in every scope.
        assertEquals(ScanCopy.TRACKING_LOST, ScanScopeCopy.coaching(ScanScope.Object, ScanCopy.TRACKING_LOST))
        assertNull(ScanScopeCopy.coaching(ScanScope.Home, null))
    }

    @Test
    fun `a home paused between rooms says where to go next`() {
        assertEquals(
            "Room 1 saved. Walk to Room 2, then tap Start. Nothing is captured on the way.",
            ScanScopeCopy.coaching(ScanScope.Home, ScanCopy.COACH_PAUSED, nextRoom = 2),
        )
        // A pause inside a room is a plain pause.
        assertEquals(ScanCopy.COACH_PAUSED, ScanScopeCopy.coaching(ScanScope.Home, ScanCopy.COACH_PAUSED))
        assertEquals("1 room", ScanScopeCopy.roomCount(1))
        assertEquals("3 rooms", ScanScopeCopy.roomCount(3))
    }

    @Test
    fun `no scope says Room Scan`() {
        val words = ScanScope.entries.flatMap {
            listOf(
                ScanScopeCopy.title(it), ScanScopeCopy.detail(it), ScanScopeCopy.how(it),
                ScanScopeCopy.start(it), ScanScopeCopy.scanning(it), ScanScopeCopy.nothingNew(it),
            )
        } + ScanScopeCopy.CHOOSER_TITLE + ScanScopeCopy.CHOOSER_BODY
        words.forEach { assertFalse(it, it.contains("Room Scan", ignoreCase = true)) }
    }

    private fun scan(scene: SyntheticScene, stops: List<Vec3>): RoomSurveyInput {
        val fusion = DenseFusion()
        val poses = RerunSyntheticRoom.poses(stops = stops)
        for (pose in poses) fusion.add(DepthBackProjection.project(scene.render(pose)))
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val trail = FloatArray(poses.size * 3)
        poses.forEachIndexed { i, p ->
            trail[i * 3] = p.x
            trail[i * 3 + 1] = p.y
            trail[i * 3 + 2] = p.z
        }
        return RoomSurveyInput(cloud.positions, cloud.normals, trail)
    }

    private fun assertWithin(what: String, expected: Float, actual: Float?) {
        assertNotNull("$what: no figure", actual)
        val error = abs(actual!! - expected) / expected
        assertTrue("$what: $actual for $expected", error <= TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.05f
    }

    @Test
    fun `an outdoor area maps the ground and has no floor plan nor ceiling`() {
        val area = ScanScope.Area
        assertEquals(area, ScanScope.fromKey("area"))
        assertEquals(ScanPreset.ROOM, area.preset)
        assertFalse(area.roomByRoom)
        assertFalse("no walls to close a plan", area.hasFloorPlan)
        assertTrue("the Map view and its coverage stay", area.mapsGround)
        assertFalse("an object has no ground to map", ScanScope.Object.mapsGround)
        assertTrue(ScanScope.Room.mapsGround && ScanScope.Home.mapsGround)
        assertEquals("Area · Oct 19, 3:12 PM", ScanScopeCopy.sessionTitle(area, 1, "Oct 19, 3:12 PM"))
        val words = listOf(
            ScanScopeCopy.title(area), ScanScopeCopy.detail(area), ScanScopeCopy.how(area),
            ScanScopeCopy.start(area), ScanScopeCopy.scanning(area), ScanScopeCopy.nothingNew(area),
        )
        words.forEach { word ->
            listOf("ceiling", "wall", "floor plan", "room").forEach { assertFalse(word, word.contains(it, ignoreCase = true)) }
        }
    }

    @Test
    fun `the export sheet offers a plan only once the survey drew one`() {
        assertEquals(PlanOffer.Measuring, PlanOffer.of(null))
        assertEquals(PlanOffer.NoDepth, PlanOffer.of(RoomSurvey.empty(SurveyGap.NoDepth)))
        assertEquals(PlanOffer.NoWalls, PlanOffer.of(RoomSurvey.empty(SurveyGap.NoWalls)))
    }
}
