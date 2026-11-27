package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sceneview.ar.ARSessionFailure
import io.github.sceneview.math.Position

/** Drop references only: native cleanup is unsafe once the owning session has closed. */
internal abstract class DemoSessionState {
    fun onFailure(fatal: Boolean): Boolean {
        if (fatal) clear()
        return fatal
    }

    protected abstract fun clear()
}

/** The SDK recreates fatal sessions; a camera resume failure keeps its anchors valid. */
internal fun DemoSessionState.onFailure(failure: ARSessionFailure): Boolean =
    onFailure(failure is ARSessionFailure.Fatal)

internal class ImageSessionState<I> : DemoSessionState() {
    var generation: Int = 0
        private set
    val detectedImages = mutableStateListOf<I>()
    override fun clear() {
        generation++
        detectedImages.clear()
    }
}

internal class OrbitalSessionState<A> : DemoSessionState() {
    var userAnchor by mutableStateOf<A?>(null)
    override fun clear() { userAnchor = null }
}

internal class PortraitSessionState<F> : DemoSessionState() {
    var latestFrame by mutableStateOf<F?>(null)
    override fun clear() { latestFrame = null }
}

internal class SurfaceSessionState<F, N, A> : DemoSessionState() {
    var latestFrame: F? = null
    var anchorNode: N? = null
    var placedAnchor by mutableStateOf<A?>(null)
    fun resetPlacement() {
        anchorNode = null
        placedAnchor = null
    }

    override fun clear() {
        latestFrame = null
        resetPlacement()
    }
}

internal class CloudAnchorSessionState<S, P, A, H, R> : DemoSessionState() {
    var arSession by mutableStateOf<S?>(null)
    var localPlacement by mutableStateOf<P?>(null)
    var resolvedAnchor by mutableStateOf<A?>(null)
    var hostAnchor by mutableStateOf<A?>(null)
    var hostFuture by mutableStateOf<H?>(null)
    var resolveFuture by mutableStateOf<R?>(null)
    val requests = CloudRequestGeneration()
    override fun clear() {
        requests.closeSession()
        arSession = null
        localPlacement = null
        resolvedAnchor = null
        hostAnchor = null
        hostFuture = null
        resolveFuture = null
    }
}

internal class GeospatialSessionState<S, P, D> : DemoSessionState() {
    var arSession by mutableStateOf<S?>(null)
    var cameraPose: P? = null
    val drops = mutableStateListOf<D>()
    val requests = CloudRequestGeneration()
    override fun clear() {
        requests.closeSession()
        arSession = null
        cameraPose = null
        drops.clear()
    }
}

internal class RecordingSessionState<S, F, A> : DemoSessionState() {
    var arSession by mutableStateOf<S?>(null)
    var latestFrame: F? = null
    val anchors = mutableStateListOf<A>()
    override fun clear() {
        arSession = null
        latestFrame = null
        anchors.clear()
    }
}

internal class AskSessionState<F, P, A> : DemoSessionState() {
    var generation: Int = 0
        private set
    var latestFrame by mutableStateOf<F?>(null)
    var pendingPanel by mutableStateOf<P?>(null)
    val panels = mutableStateListOf<P>()
    val placedProps = mutableStateListOf<A>()
    override fun clear() {
        latestFrame = null
        pendingPanel = null
        generation++
        panels.clear()
        placedProps.clear()
    }
}

/**
 * AR Measure's anchors on the board and the object dimensions, their last world positions and the
 * session that made them. The scaffold replaces the scene on any failure, which closes the session:
 * the anchors are then forgotten, never detached, and the screen's dispose has nothing left to free.
 * The board keeps its mode, so the dock under the failure card stays the one the user was on.
 */
internal class MeasureSessionState<A, S> : DemoSessionState() {
    var session by mutableStateOf<S?>(null)
    var board by mutableStateOf(MeasureBoard<A>())
    var objectMeasure by mutableStateOf(ObjectMeasure<A>())
    var positions by mutableStateOf<Map<A, Position>>(emptyMap())
    var objectPositions by mutableStateOf<Map<A, Position>>(emptyMap())
    override fun clear() {
        session = null
        board = MeasureBoard(mode = board.mode)
        objectMeasure = ObjectMeasure()
        positions = emptyMap()
        objectPositions = emptyMap()
    }
}

/** 3D Scan's last frame, read when a capture starts. */
internal class ScanSessionState<F> : DemoSessionState() {
    var latestFrame by mutableStateOf<F?>(null)
    override fun clear() { latestFrame = null }
}

/**
 * Finger Drawing's strokes and the anchors they hang on (#5208). A stroke is drawn in its
 * anchor's space: once a fatal failure closed the session, the strokes are dropped with their
 * anchors, which are forgotten, never detached. Stroke ids keep counting, so a new stroke never
 * takes the key of one that left.
 */
internal class DrawingSessionState<A> : DemoSessionState() {
    var drawing by mutableStateOf(Drawing())
    var anchorOf by mutableStateOf(emptyMap<Int, A>())
    var healthOf by mutableStateOf(emptyMap<A, AnchorHealth>())
    override fun clear() {
        drawing = Drawing(nextId = drawing.nextId)
        anchorOf = emptyMap()
        healthOf = emptyMap()
    }
}

/**
 * Streetscape's geometries and the session that made them (#5208): the scene reads each one's
 * tracking state every frame, and the coverage check calls the session. [requests] is closed
 * first, so the coverage check that [session] leaving cancels keeps off the closed future.
 */
internal class StreetscapeSessionState<S, G> : DemoSessionState() {
    var session by mutableStateOf<S?>(null)
    val geometries = mutableStateListOf<G>()
    val requests = CloudRequestGeneration()
    override fun clear() {
        requests.closeSession()
        session = null
        geometries.clear()
    }
}

/** Feature demos must also forget the placement adapter's anchor and cached planes. */
internal class FeaturePlacementSessionState<P, T> : DemoSessionState() {
    var placement by mutableStateOf<P?>(null)
    var planes by mutableStateOf<List<T>>(emptyList())
    override fun clear() {
        placement = null
        planes = emptyList()
    }
}
