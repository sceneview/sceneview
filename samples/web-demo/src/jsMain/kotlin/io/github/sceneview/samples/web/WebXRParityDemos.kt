package io.github.sceneview.samples.web

import io.github.sceneview.web.xr.WebXRSession
import io.github.sceneview.web.xr.XRAnchorNode
import io.github.sceneview.web.xr.XRDepthInfo
import io.github.sceneview.web.xr.XRFeature
import io.github.sceneview.web.xr.XRHandNode
import io.github.sceneview.web.xr.XRImageTrackingNode
import io.github.sceneview.web.xr.XRSessionMode
import io.github.sceneview.web.xr.getDepthInformation
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

/**
 * Compile-checked usage samples for the four WebXR parity composables added by
 * #1778. These functions are not wired into the demo UI yet (they require a
 * WebXR-capable device — see Pixel/Chrome flag for depth/hand/image tracking,
 * or Quest 3 for full coverage). They exist so that:
 *
 * 1. The Kotlin compiler catches API drift on every CI run.
 * 2. Anyone reading the demo source learns the canonical call patterns.
 * 3. The PR can be QA'd manually by wiring any function to a button.
 *
 * See `llms.txt` "WebXR — Depth / Hand / Image / Anchors" for the same
 * patterns in the SDK reference.
 */

/**
 * Depth-sensing demo — render a cube that gets occluded by the real world.
 *
 * Real device required:
 * - Chrome on Android with ARCore (supports depth-sensing).
 * - Quest 3 browser.
 */
fun launchDepthSensingDemo(canvas: HTMLCanvasElement) {
    WebXRSession.create(
        canvas = canvas,
        mode = XRSessionMode.IMMERSIVE_AR,
        features = WebXRSession.Features(
            required = arrayOf(XRFeature.HIT_TEST),
            optional = arrayOf(XRFeature.DEPTH_SENSING),
        ),
        onError = { msg -> console.error("Depth demo: $msg") },
        onReady = { session ->
            session.loadModel(
                "https://sceneview.github.io/models/platforms/DamagedHelmet.glb"
            )
            session.onFrame = { frame, viewerPose ->
                val view = viewerPose?.views?.firstOrNull()
                if (view != null) {
                    val raw = frame.getDepthInformation(view)
                    val depth = XRDepthInfo.from(raw)
                    if (depth != null && depth.isCpuReadable) {
                        val centerDepth = depth.atNormalized(0.5, 0.5)
                        // Use centerDepth (meters) to push/pull virtual content
                        // along the camera ray, or to short-circuit hit testing.
                        console.log("Depth at view center: $centerDepth m")
                    }
                }
            }
            session.start()
        },
    )
}

/**
 * Hand-tracking demo — render markers at the index/thumb tips of the left hand.
 *
 * Real device required: Quest 3 / Quest Pro / any WebXR runtime exposing
 * `hand-tracking`.
 */
fun launchHandTrackingDemo(canvas: HTMLCanvasElement) {
    WebXRSession.create(
        canvas = canvas,
        mode = XRSessionMode.IMMERSIVE_VR,
        features = WebXRSession.Features(
            optional = arrayOf(XRFeature.HAND_TRACKING),
        ),
        onError = { msg -> console.error("Hand-tracking demo: $msg") },
        onReady = { session ->
            val leftHand = XRHandNode(XRHandNode.Handedness.LEFT)
                .joint(XRHandNode.Joint.INDEX_TIP) { pose ->
                    // pose.transform.position is the index fingertip in
                    // session.referenceSpace coordinates.
                }
                .joint(XRHandNode.Joint.THUMB_TIP) { pose -> /* ... */ }
                .joint(XRHandNode.Joint.WRIST) { pose -> /* ... */ }

            val rightHand = XRHandNode(XRHandNode.Handedness.RIGHT)
                .joint(XRHandNode.Joint.INDEX_TIP) { pose -> /* ... */ }

            leftHand.onHandFound = { _ -> console.log("Left hand acquired") }
            leftHand.onHandLost = { console.log("Left hand lost") }

            session.onFrame = { frame, _ ->
                leftHand.update(frame, session.referenceSpace)
                rightHand.update(frame, session.referenceSpace)
            }
            session.start()
        },
    )
}

/**
 * Image-tracking demo — anchor content to a printed marker.
 *
 * Real device required: Android Chrome with `image-tracking` enabled (flag /
 * origin trial).
 *
 * @param markerImageUrl A URL pointing at the marker `ImageBitmap` source.
 *   Must be served from the same origin or with CORS.
 */
fun launchImageTrackingDemo(canvas: HTMLCanvasElement, markerImageUrl: String) {
    // Step 1: build the trackedImages init dict — uses dynamic JS because the
    // spec passes ImageBitmap (an external Web API), not a Kotlin type.
    val markerImage = window.asDynamic().createImageBitmap
    // Real-world code would `fetch(markerImageUrl).then(r => r.blob()).then(b => createImageBitmap(b))`
    // first, then resolve into the trackedImages array. Stubbed out here for compile-check only.
    val trackedImages = js("[]")

    val sessionOptions = js("{}")
    sessionOptions.requiredFeatures = arrayOf(XRFeature.IMAGE_TRACKING, XRFeature.HIT_TEST)
    sessionOptions.trackedImages = trackedImages

    WebXRSession.create(
        canvas = canvas,
        mode = XRSessionMode.IMMERSIVE_AR,
        features = WebXRSession.Features(
            required = arrayOf(XRFeature.IMAGE_TRACKING, XRFeature.HIT_TEST),
        ),
        onError = { msg -> console.error("Image-tracking demo: $msg") },
        onReady = { session ->
            val poster = XRImageTrackingNode(index = 0) { pose, result ->
                // pose.transform.matrix is the 4x4 column-major model matrix of the marker.
                // result.measuredWidthInMeters gives the runtime-measured width.
                val w = result.measuredWidthInMeters
                console.log("Marker tracked — width=${w}m, state=${result.trackingState}")
            }
            poster.onFound = { _ -> console.log("Marker first seen") }
            poster.onLost = { console.log("Marker lost") }

            session.onFrame = { frame, _ ->
                poster.update(frame, session.referenceSpace)
            }
            session.start()
        },
    )
}

/**
 * Anchors demo — drop an anchor on the first hit-test result of each tap and
 * track it across frames.
 *
 * Real device required: Chrome on Android (ARCore) with the `anchors` feature.
 */
fun launchAnchorsDemo(canvas: HTMLCanvasElement) {
    WebXRSession.create(
        canvas = canvas,
        mode = XRSessionMode.IMMERSIVE_AR,
        features = WebXRSession.Features(
            required = arrayOf(XRFeature.HIT_TEST),
            optional = arrayOf(XRFeature.ANCHORS),
        ),
        onError = { msg -> console.error("Anchors demo: $msg") },
        onReady = { session ->
            val anchors: MutableList<XRAnchorNode> = mutableListOf()
            var lastHitTransform: dynamic = null

            session.onHitTest = { pose -> lastHitTransform = pose.transform }

            session.onInputSelect = { _, _ ->
                val transform = lastHitTransform
                if (transform != null) {
                    // Promise<XRAnchor> created from the latest hit-test transform.
                    val anchorPromise = session.xrSession.asDynamic().createAnchor(
                        transform,
                        session.referenceSpace,
                    )
                    anchorPromise.then { anchor: io.github.sceneview.web.xr.XRAnchor ->
                        val node = XRAnchorNode(anchor) { _ ->
                            // pose.transform.matrix is the per-frame anchor pose.
                        }
                        node.onAttached = { _ -> console.log("Anchor attached") }
                        node.onLost = { console.log("Anchor lost") }
                        anchors += node
                    }
                }
            }

            session.onFrame = { frame, _ ->
                val it = anchors.iterator()
                while (it.hasNext()) {
                    val node = it.next()
                    if (!node.update(frame, session.referenceSpace)) {
                        it.remove()
                    }
                }
            }

            session.onSessionEnd = {
                anchors.forEach { it.detach() }
                anchors.clear()
            }

            session.start()
        },
    )
}
