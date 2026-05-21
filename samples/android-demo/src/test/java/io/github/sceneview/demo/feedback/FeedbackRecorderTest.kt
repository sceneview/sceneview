package io.github.sceneview.demo.feedback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * State-machine tests for [FeedbackRecorder] — the process-wide bridge between
 * the recording service and the Compose feedback flow (#1930 review).
 *
 * Covers: the monotonic session id that drops a stale [FeedbackRecorder.setDone]
 * from a superseded recording, and the upload-in-progress guard that defers
 * media deletion so a dialog dismiss can't pull the file from under an upload.
 */
class FeedbackRecorderTest {

    private val tmpFiles = mutableListOf<File>()

    private fun newFile(name: String): File =
        File.createTempFile(name, ".tmp").also {
            it.writeText("x")
            tmpFiles += it
        }

    @Before
    fun setUp() {
        FeedbackRecorder.reset()
    }

    @After
    fun tearDown() {
        FeedbackRecorder.reset()
        tmpFiles.forEach { it.delete() }
        tmpFiles.clear()
    }

    @Test
    fun `begin session transitions to Recording and mints a fresh id`() {
        val first = FeedbackRecorder.beginSession()
        assertEquals(RecordingState.Recording, FeedbackRecorder.state.value)

        val second = FeedbackRecorder.beginSession()
        assertTrue("session id is monotonic", second > first)
        assertTrue(FeedbackRecorder.isCurrentSession(second))
        assertFalse(FeedbackRecorder.isCurrentSession(first))
    }

    @Test
    fun `setDone for the current session publishes the recording`() {
        val session = FeedbackRecorder.beginSession()
        val video = newFile("video")
        val recording = FeedbackRecording(video, null, 1_000L)

        FeedbackRecorder.setDone(recording, session)

        val state = FeedbackRecorder.state.value
        assertTrue(state is RecordingState.Done)
        assertSame(recording, (state as RecordingState.Done).recording)
    }

    @Test
    fun `setDone from a superseded session is dropped and its media deleted`() {
        val staleSession = FeedbackRecorder.beginSession()
        // The user re-records quickly — a new session supersedes the stale one.
        FeedbackRecorder.beginSession()

        val staleVideo = newFile("stale-video")
        val staleAudio = newFile("stale-audio")
        FeedbackRecorder.setDone(
            FeedbackRecording(staleVideo, staleAudio, 500L),
            staleSession,
        )

        // The stale callback must not clobber the new Recording state...
        assertEquals(RecordingState.Recording, FeedbackRecorder.state.value)
        // ...and its orphaned media must be cleaned up.
        assertFalse("stale video deleted", staleVideo.exists())
        assertFalse("stale audio deleted", staleAudio.exists())
    }

    @Test
    fun `setFailed from a superseded session is dropped`() {
        val staleSession = FeedbackRecorder.beginSession()
        FeedbackRecorder.beginSession()

        FeedbackRecorder.setFailed("stale failure", staleSession)

        assertEquals(RecordingState.Recording, FeedbackRecorder.state.value)
    }

    @Test
    fun `reset deletes the finished recording media`() {
        val session = FeedbackRecorder.beginSession()
        val video = newFile("video")
        val audio = newFile("audio")
        FeedbackRecorder.setDone(FeedbackRecording(video, audio, 1_000L), session)

        FeedbackRecorder.reset()

        assertEquals(RecordingState.Idle, FeedbackRecorder.state.value)
        assertFalse(video.exists())
        assertFalse(audio.exists())
        assertNull(FeedbackRecorder.category)
    }

    @Test
    fun `reset during an upload defers deletion until endUpload`() {
        val session = FeedbackRecorder.beginSession()
        val video = newFile("video")
        val audio = newFile("audio")
        FeedbackRecorder.setDone(FeedbackRecording(video, audio, 1_000L), session)

        // An upload starts reading the media, then the dialog is dismissed.
        FeedbackRecorder.beginUpload()
        FeedbackRecorder.reset()

        // The state is cleared, but the files survive while the upload holds them.
        assertEquals(RecordingState.Idle, FeedbackRecorder.state.value)
        assertTrue("video still readable mid-upload", video.exists())
        assertTrue("audio still readable mid-upload", audio.exists())

        // Once the upload releases the media, the deferred deletion runs.
        FeedbackRecorder.endUpload()
        assertFalse(video.exists())
        assertFalse(audio.exists())
    }

    @Test
    fun `endUpload without a deferred reset leaves the media in place`() {
        val session = FeedbackRecorder.beginSession()
        val video = newFile("video")
        FeedbackRecorder.setDone(FeedbackRecording(video, null, 1_000L), session)

        FeedbackRecorder.beginUpload()
        FeedbackRecorder.endUpload()

        // No reset happened — endUpload must not delete anything.
        assertTrue(video.exists())
    }
}
