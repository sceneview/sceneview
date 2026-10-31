package io.github.sceneview.demo.demos.internal

/*
 * What a 3D Scan captures (#4647): one object, one room, or a whole home scanned room by room in
 * one session. The choice is asked first, remembered, and every part of the capture that depends
 * on it reads it from here: the coaching, the photo spacing, how far the depth is kept, and the
 * voxel the 3D model is rebuilt at. Nothing in a preset is a label only — each field is passed to
 * the capture (RerunScan.kt) or to the model's TSDF.
 */

/** What is being scanned. [key] is what the choice is remembered under. */
enum class ScanScope(val key: String) {
    /** A single item on a table or the floor: a close orbit, a tight volume. */
    Object("object"),

    /** One room: walls, floor and ceiling — the flow 3D Scan had before #4647. */
    Room("room"),

    /** Several rooms in one session, one after the other, each with its own plan. */
    Home("home"),

    /**
     * Outside (#4720): a garden, a yard, a terrace. The ground and what stands on it, with no
     * ceiling to look for and no walls to close a plan: a map of the ground, never a floor plan.
     */
    Area("area"),
    ;

    /** How this scope's capture is taken and rebuilt. */
    val preset: ScanPreset
        get() = when (this) {
            Object -> ScanPreset.OBJECT
            Room, Home, Area -> ScanPreset.ROOM
        }

    /** Whether a finished capture is one room of several: Next room, then a summary. */
    val roomByRoom: Boolean get() = this == Home

    /**
     * Whether the capture is surveyed into a floor plan — walls, area, ceiling height: rooms are;
     * an object is not, nor an outdoor area, which has no walls to close one nor a ceiling.
     */
    val hasFloorPlan: Boolean get() = this == Room || this == Home

    /**
     * Whether the capture maps the ground it covered, seen from above (the Map view and its
     * coverage): rooms and an outdoor area do; an object stands on a few square centimetres.
     */
    val mapsGround: Boolean get() = this != Object

    companion object {
        /** The scope asked first when nothing was chosen before: what 3D Scan always did. */
        val DEFAULT = Room

        /** The scope remembered under [key]; [DEFAULT] for none, or for a key this build does not know. */
        fun fromKey(key: String?): ScanScope = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * How a capture is taken and rebuilt.
 *
 * @property farM depth further than this from the phone is left out of the points and the model:
 *   for an object, the room behind it.
 * @property photoStepM a photo is taken each time the phone has moved this far ([KeyframeGate])…
 * @property photoTurnDegrees …or turned this much: an orbit close to an object needs them denser.
 * @property maxPhotos the photo cap: past it the points and the path keep recording.
 * @property modelVoxelM the voxel of the TSDF the 3D model is meshed from ([RerunTsdf]): finer for
 *   an object seen from close, where the depth carries the detail.
 */
data class ScanPreset(
    val farM: Float,
    val photoStepM: Float,
    val photoTurnDegrees: Float,
    val maxPhotos: Int,
    val modelVoxelM: Float,
) {
    init {
        require(farM > DepthBackProjection.NEAR_M && farM <= DepthBackProjection.FAR_M) { "farM $farM" }
        require(photoStepM > 0f && photoTurnDegrees > 0f && maxPhotos > 0) { "photo spacing" }
        require(modelVoxelM > 0f) { "modelVoxelM $modelVoxelM" }
    }

    companion object {
        /**
         * An object at arm's length: depth kept to 1.5 m, a photo every 8 cm or 6°, and a model
         * at half the room's voxel — ARCore's 160 × 90 raw depth puts a pixel ~8 mm apart at 1 m.
         */
        val OBJECT = ScanPreset(
            farM = 1.5f,
            photoStepM = 0.08f,
            photoTurnDegrees = 6f,
            maxPhotos = KeyframeGate.MAX_PHOTOS,
            modelVoxelM = RerunTsdf.VOXEL_M / 2f,
        )

        /** A room: the depth ARCore gives (to 5 m), a photo every 15 cm or 10°, the 2.5 cm model. */
        val ROOM = ScanPreset(
            farM = DepthBackProjection.FAR_M,
            photoStepM = KeyframeGate.MIN_STEP_M,
            photoTurnDegrees = KeyframeGate.MIN_TURN_DEGREES,
            maxPhotos = KeyframeGate.MAX_PHOTOS,
            modelVoxelM = RerunTsdf.VOXEL_M,
        )
    }
}

/**
 * A whole home in progress (#4647): the rooms saved so far in this session, in order. Immutable
 * and pure, so the screen holds it as state and a test holds its numbering and its totals.
 *
 * [id] tells one home from the next (#4683): a room's survey runs off the main thread and can
 * finish after a new home has started; it lands only in the home it was measured for ([measuredIn]).
 */
data class HomeScan(val rooms: List<HomeRoom> = emptyList(), val id: Int = 0) {
    /** A new, empty home, told apart from this one. */
    fun next(): HomeScan = HomeScan(id = id + 1)

    /** The number the room being scanned now takes: one past the rooms saved. */
    val nextNumber: Int get() = rooms.size + 1

    /** Whether every room saved has been measured. */
    val measured: Boolean get() = rooms.all { it.survey != null }

    /** The home with the room just saved as [id], numbered [nextNumber]; it is measured later ([measured]). */
    fun add(id: String?, title: String): HomeScan = copy(rooms = rooms + HomeRoom(nextNumber, id, title, survey = null))

    /** The home with room [number] given its [survey], once measured off the main thread. */
    fun measured(number: Int, survey: RoomSurvey): HomeScan =
        copy(rooms = rooms.map { if (it.number == number) it.copy(survey = survey) else it })

    /**
     * [measured] for home [home] only: a survey of another home — one left before its rooms were
     * all measured — changes nothing here.
     */
    fun measuredIn(home: Int, number: Int, survey: RoomSurvey): HomeScan =
        if (home == id) measured(number, survey) else this

    /**
     * The home's Rooms block: one row per room, named [roomName] of its number, then the total —
     * none while a room is still being measured or was not measured, rather than one that leaves it out.
     */
    fun areas(roomName: (Int) -> String): PlanAreas = PlanAreas.of(
        rooms.map { room ->
            room.survey?.let { PlanAreas.room(roomName(room.number), it) }
                ?: RoomAreas(roomName(room.number), area = null, volume = null)
        },
    )
}

/** One room of a [HomeScan]: its [number] in the home, the kept session [id], and its [survey] once read. */
data class HomeRoom(val number: Int, val id: String?, val title: String, val survey: RoomSurvey?)

/** The words of the scope choice and of each scope's capture, pure so a test holds them. */
object ScanScopeCopy {
    const val CHOOSER_TITLE = "What are you scanning?"
    const val CHOOSER_BODY = "The guidance and the 3D result are set for what you pick."

    fun title(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "An object"
        ScanScope.Room -> "A room"
        ScanScope.Home -> "A whole home"
        ScanScope.Area -> "An outdoor area"
    }

    fun detail(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "One item on a table or the floor, like a chair or a vase."
        ScanScope.Room -> "One room, with its walls, floor and ceiling."
        ScanScope.Home -> "Several rooms, one after the other, without stopping."
        ScanScope.Area -> "A garden, a yard or a terrace: the ground and what stands on it."
    }

    /** How the scan goes, and what it gives: what a tradesperson needs to know before starting. */
    fun how(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "Walk around it at arm's length. Anything further than 1.5 m is left out."
        ScanScope.Room -> "Walk along the walls. You get a 3D model and a floor plan with measurements."
        ScanScope.Home -> "Finish a room, then start the next. You get a plan of each room and the total."
        ScanScope.Area -> "Walk slowly across it. You get a 3D model and a map of the ground."
    }

    /** The chooser's primary action. */
    fun start(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "Scan an object"
        ScanScope.Room -> "Scan a room"
        ScanScope.Home -> "Scan your home"
        ScanScope.Area -> "Scan an area"
    }

    /** What the capture's status line calls the scan: `Object`, `Room`, `Room 2`. */
    fun label(scope: ScanScope, roomNumber: Int): String = when (scope) {
        ScanScope.Object -> "Object"
        ScanScope.Room -> "Room"
        ScanScope.Home -> "Room $roomNumber"
        ScanScope.Area -> "Area"
    }

    /** The name a capture is kept under: `Object · Oct 19, 3:12 PM`, `Room 2 · Oct 19, 3:12 PM`, `Area · …`. */
    fun sessionTitle(scope: ScanScope, roomNumber: Int, date: String): String = "${label(scope, roomNumber)} · $date"

    /** The coaching line while nothing else needs saying. */
    fun scanning(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "Circle the object slowly, at arm's length"
        ScanScope.Room, ScanScope.Home -> "Walk slowly along the walls, with some floor in view"
        ScanScope.Area -> "Walk slowly, with the ground in view"
    }

    /** The coaching line when the last seconds added nothing. */
    fun nothingNew(scope: ScanScope): String = when (scope) {
        ScanScope.Object -> "Point at the object, 30 cm to 1.5 m away"
        ScanScope.Room, ScanScope.Home -> ScanCopy.COACH_NOTHING_NEW
        ScanScope.Area -> "Point at the ground or what stands on it, 1 to 3 m away"
    }

    /**
     * The capture's coaching [line] ([ScanCopy.coaching]) in [scope]'s words: its two guidance
     * lines follow what is scanned, and a home paused before room [nextRoom] says how to get there.
     * A room's scan given the [walls] it recognised guides from wall to wall instead: [done] once
     * they close the room, else the [turn] to the next gap ([ScanGuide.cue]); and when nothing new
     * comes while a wall is missing, it says how to catch a plain wall — or, the phone looking at
     * the floor or the ceiling (not [facingWall]), to raise it to the walls (#4540 live walls).
     * Facing a wall seen only in part ([wallToFinish]), it asks to walk along it first.
     * Every other line — tracking lost, the photo cap, the detail — says the same in every scope.
     */
    @Suppress("LongParameterList") // one input per thing the line can say
    fun coaching(
        scope: ScanScope,
        line: String?,
        nextRoom: Int? = null,
        walls: Int? = null,
        turn: ScanTurn? = null,
        done: Boolean = false,
        facingWall: Boolean = true,
        wallToFinish: Boolean = false,
        floorLeft: Boolean = false,
    ): String? {
        val found = walls ?: -1
        val rooms = found >= 0 && scope.hasFloorPlan
        return when {
            nextRoom != null && nextRoom > 1 && line == ScanCopy.COACH_PAUSED -> between(nextRoom - 1, nextRoom)
            // Walls closed over a floor the map still hatches: the floor is what is left (QA-11).
            rooms && done && line in DONE_OVER -> if (floorLeft) ScanCopy.FLOOR_LEFT else ScanCopy.ROOM_DONE
            line == ScanCopy.COACH_SCANNING && rooms && wallToFinish -> ScanCopy.WALL_TO_FINISH
            line == ScanCopy.COACH_SCANNING && rooms && turn != null && found >= 1 -> ScanCopy.turnHint(turn)
            line == ScanCopy.COACH_SCANNING && rooms -> ScanCopy.wallGuide(found)
            line == ScanCopy.COACH_SCANNING -> scanning(scope)
            line == ScanCopy.COACH_NOTHING_NEW && rooms && found < ScanGuide.ROOM_WALLS ->
                if (facingWall) ScanCopy.COACH_BLANK_WALL else ScanCopy.COACH_FACE_WALLS
            line == ScanCopy.COACH_NOTHING_NEW -> nothingNew(scope)
            else -> line
        }
    }

    /** The lines a complete room speaks over: nothing is wrong, there is just nothing left to add. */
    private val DONE_OVER = setOf(ScanCopy.COACH_SCANNING, ScanCopy.COACH_STILL, ScanCopy.COACH_NOTHING_NEW)

    // Whole home (#4647): the room's review, the pause between rooms, and the summary.
    const val NEXT_ROOM = "Next room"
    const val SAVE_AND_FINISH = "Save and finish"
    const val FINISH_HOME = "Finish"
    const val START_ROOM = "Start"
    const val HOME_TITLE = "Your home"
    const val SCAN_ANOTHER_ROOM = "Scan another room"
    const val OPEN_PLAN = "Open plan"
    const val MEASURING = "Measuring…"

    /** `Review Room 2`. */
    fun reviewTitle(roomNumber: Int): String = "Review Room $roomNumber"

    const val REVIEW_DETAIL =
        "Save this room and go on to the next one, or save and finish to see the plan of every room."
    const val EMPTY_DETAIL = "Scan this room, or finish with the rooms already saved."

    /** Between two rooms: the capture waits, paused, while the phone is carried to the next one. */
    fun between(saved: Int, next: Int): String =
        "Room $saved saved. Walk to Room $next, then tap Start. Nothing is captured on the way."

    /** `2 rooms` under the summary's title. */
    fun roomCount(count: Int): String = "$count ${ScanCopy.label(count, "room", "rooms")}"
}
