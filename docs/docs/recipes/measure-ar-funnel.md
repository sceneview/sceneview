---
title: Measure your AR funnel
description: Instrument the AR drop-off — session created, tracking ready, first placement, tracking lost, session failure — from your own app, using SceneView's public ARSceneView callbacks. SceneView itself ships no telemetry.
---

# Measure your AR funnel

**Intent:** "Half my users never place a model. Where do they drop off?"

!!! info "SceneView ships no telemetry"
    The SDK sends nothing anywhere. There is no analytics dependency, no opt-out flag, no
    endpoint. Every number below is produced **by your app, in your analytics account**,
    from callbacks that are already part of the public `ARSceneView` API. If you want no
    measurement at all, pass none of these lambdas and nothing happens.

## The five steps worth counting

| Step | Signal | What a drop here means |
|---|---|---|
| `ar_session_created` | `onSessionCreated` | ARCore is installed and permission was granted. |
| `ar_tracking_ready` | `onTrackingFailureChanged` fires with `null` | The camera found enough of the world to anchor to. |
| `ar_first_placement` | your own tap handler creates the first `Anchor` | The user understood the interaction. |
| `ar_tracking_lost` | `onTrackingFailureChanged` fires non-`null` | Lighting, motion or a featureless surface. |
| `ar_session_failed` | `onSessionFailure` | A typed, actionable reason — not a stack trace. |

## A sink you own

Keep the analytics vendor out of your AR code. One interface, one implementation per
build flavour, and your screen stays testable:

```kotlin
interface ArFunnel {
    fun log(event: String, params: Map<String, String> = emptyMap())
}
```

A Firebase Analytics implementation is one line of body — any sink works (Amplitude,
PostHog, your own backend, or `Log.d` in debug builds):

```kotlin
class FirebaseArFunnel(private val analytics: FirebaseAnalytics) : ArFunnel {
    override fun log(event: String, params: Map<String, String>) =
        analytics.logEvent(event) { params.forEach { (k, v) -> param(k, v) } }
}
```

## Wiring it to `ARSceneView`

`onTrackingFailureChanged` is the funnel middle: SceneView calls it **only when the reason
changes**, so it is already de-duplicated — no per-frame guard needed. The first `null` is
"tracking ready"; every non-`null` is a stall with a named cause.

```kotlin
@Composable
fun ArScreen(funnel: ArFunnel) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var everTracked by remember { mutableStateOf(false) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionCreated = { funnel.log("ar_session_created") },
        onTrackingFailureChanged = { reason ->
            if (reason == null) {
                // First null = the session reached a usable state. Later nulls are
                // recoveries, worth their own event so you can see how often users
                // fight their way back instead of quitting.
                funnel.log(if (everTracked) "ar_tracking_recovered" else "ar_tracking_ready")
                everTracked = true
            } else {
                funnel.log("ar_tracking_lost", mapOf("reason" to reason.name))
            }
        },
        onTouchEvent = { _, hitResult ->
            // Your own tap handler — SceneView never places anything for you, so this
            // is the only place that knows a placement happened.
            if (anchor == null) {
                hitResult?.createAnchorOrNull()?.let {
                    anchor = it
                    funnel.log("ar_first_placement")
                }
            }
            false // let SceneView keep handling the gesture
        },
        onSessionFailure = { failure ->
            // Typed, so the label is stable across ARCore versions and you can route
            // "install ARCore" separately from "device not supported".
            funnel.log(
                "ar_session_failed",
                mapOf("reason" to failure::class.simpleName.orEmpty())
            )
        }
    ) {
        anchor?.let { placed ->
            AnchorNode(anchor = placed) {
                modelInstance?.let { ModelNode(modelInstance = it, scaleToUnits = 0.3f) }
            }
        }
    }
}
```

## Reading the result

- `ar_session_created` → `ar_tracking_ready` measures **the device and the room**. A wide
  gap means users point at plain floors — add a scan hint.
- `ar_tracking_ready` → `ar_first_placement` measures **your UI**. This is the step an
  onboarding overlay actually moves.
- `ar_session_failed` split by `reason` tells you how much of the loss is not yours to
  fix: `ArCoreNotInstalled` and `DeviceNotCompatible` need a fallback 3D `SceneView`, not
  a better AR tutorial. The full taxonomy is in
  [`ARSessionFailure`](https://github.com/sceneview/sceneview/blob/main/arsceneview/src/main/java/io/github/sceneview/ar/ARSessionFailure.kt).

Log no camera frames, depth data or `Pose` values: the funnel needs counts, not geometry.
