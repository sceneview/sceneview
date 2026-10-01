package io.github.sceneview.demo.telemetry

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import io.github.sceneview.demo.BuildConfig

/** [PushBackend] on Firebase Cloud Messaging. Never throws: no Play services means no push. */
class FirebasePushBackend : PushBackend {

    override fun enable(topics: List<String>) {
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            topics.forEach { topic ->
                val task = messaging.subscribeToTopic(topic)
                if (BuildConfig.DEBUG) {
                    task.addOnCompleteListener { Log.d(TAG, "topic $topic subscribed ok=${it.isSuccessful}") }
                }
            }
            if (BuildConfig.DEBUG) {
                messaging.token.addOnSuccessListener { Log.d(TAG, "FCM token: $it") }
            }
        }.onFailure { Log.w(TAG, "FCM unavailable (no Play services?)", it) }
    }

    override fun disable(topics: List<String>, onDone: (left: Boolean, deleted: Boolean) -> Unit) {
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            val leaving = topics.map { messaging.unsubscribeFromTopic(it) }
            Tasks.whenAllComplete(leaving).addOnCompleteListener {
                val left = leaving.all { it.isSuccessful }
                messaging.deleteToken().addOnCompleteListener { deleted ->
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "topics left ok=$left, FCM token deleted ok=${deleted.isSuccessful}")
                    }
                    onDone(left, deleted.isSuccessful)
                }
            }
        }.onFailure {
            Log.w(TAG, "FCM unavailable (no Play services?)", it)
            onDone(false, false)
        }
    }

    private companion object {
        const val TAG = "DemoTelemetry"
    }
}
