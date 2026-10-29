package io.github.sceneview.demo.labs

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Where this install stands with SceneView Labs, the early-access programme for features that
 * still need testing on real phones (mostly AR, which an emulator cannot run).
 *
 * Access is earned by helping, never bought: the first report sent from the in-app reporter
 * ([io.github.sceneview.demo.feedback.BugReportSheet]) joins the programme. Joining turns Labs
 * on at once; the switch on the Home card and in About turns it off and on again afterwards.
 */
enum class LabsAccess {
    /** No report sent yet: the Home card invites, Labs demos stay hidden. */
    NotJoined,

    /** Joined, switched off: Labs demos stay hidden, the switch can bring them back. */
    Off,

    /** Joined and switched on: Labs demos are listed, badged, and open. */
    On,
}

/** [LabsAccess] from the two stored facts. Switched on without having joined still reads as not joined. */
fun labsAccessOf(joined: Boolean, enabled: Boolean): LabsAccess = when {
    !joined -> LabsAccess.NotJoined
    enabled -> LabsAccess.On
    else -> LabsAccess.Off
}

/** The two facts Labs persists. An interface so the rules are tested on the JVM without a device. */
interface LabsStore {
    var joined: Boolean
    var enabled: Boolean
}

/** [LabsStore] in the app's own `SharedPreferences`, local to this install, never synced or sent. */
class LabsPrefs(context: Context) : LabsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var joined: Boolean
        get() = prefs.getBoolean(KEY_JOINED, false)
        set(value) = prefs.edit { putBoolean(KEY_JOINED, value) }

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    private companion object {
        const val FILE = "sceneview_demo_labs"
        const val KEY_JOINED = "joined"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * The process-wide Labs state, observable from Compose: the Home catalogue, the deep-link gate
 * and the bug report all read [access], so a report sent from any screen opens Labs everywhere
 * on the next frame.
 *
 * [init] attaches the persistent store at process start ([io.github.sceneview.demo.DemoApplication]).
 * Without a store (previews, snapshot tests) the state lives in memory and starts [LabsAccess.NotJoined].
 */
object Labs {
    /** Current access. Written only through [join] and [setEnabled]. */
    var access: LabsAccess by mutableStateOf(LabsAccess.NotJoined)
        private set

    private var store: LabsStore? = null

    /** Whether Labs demos are listed and open. */
    val enabled: Boolean get() = access == LabsAccess.On

    /** Reads the stored state; call once per process. */
    fun init(context: Context) = attach(LabsPrefs(context))

    /** Attaches [store] and adopts what it holds. */
    fun attach(store: LabsStore) {
        this.store = store
        access = labsAccessOf(store.joined, store.enabled)
    }

    /**
     * A report was handed off (browser or share sheet): the first one joins and turns Labs on.
     * Later reports change nothing, so a tester who switched Labs off keeps it off.
     */
    fun onReportSent() {
        if (access != LabsAccess.NotJoined) return
        store?.let {
            it.joined = true
            it.enabled = true
        }
        access = LabsAccess.On
    }

    /** The Labs switch. Ignored before joining: there is no switch to flip yet. */
    fun setEnabled(on: Boolean) {
        if (access == LabsAccess.NotJoined) return
        store?.enabled = on
        access = if (on) LabsAccess.On else LabsAccess.Off
    }

    /** The `Labs` row of a bug report, so a maintainer knows which features the tester could see. */
    fun reportLine(): String = when (access) {
        LabsAccess.NotJoined -> "not joined"
        LabsAccess.Off -> "joined, off"
        LabsAccess.On -> "on"
    }
}

/** Test tags for the Labs surfaces. */
object LabsTestTags {
    const val HOME_CARD = "labs_home_card"
    const val JOIN = "labs_join"
    const val SWITCH = "labs_switch"
    const val GATE = "labs_gate"
}
