package io.github.sceneview.demo.feedback

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Round-trip, ordering and de-dup tests for the local submitted-feedback index. */
@RunWith(RobolectricTestRunner::class)
class SubmittedFeedbackStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun entry(
        issueNumber: Int,
        createdAt: Long,
        category: FeedbackCategory = FeedbackCategory.BUG,
    ) = SubmittedFeedback(
        feedbackId = "fid-$issueNumber",
        issueNumber = issueNumber,
        issueUrl = "https://github.com/sceneview/sceneview/issues/$issueNumber",
        category = category,
        title = "ticket $issueNumber",
        createdAt = createdAt,
    )

    @Test
    fun `store is empty by default`() {
        assertTrue(SubmittedFeedbackStore.all(context).isEmpty())
    }

    @Test
    fun `add round-trips every field`() {
        val e = entry(101, createdAt = 1_000L, category = FeedbackCategory.IDEA)

        SubmittedFeedbackStore.add(context, e)

        assertEquals(e, SubmittedFeedbackStore.all(context).single())
    }

    @Test
    fun `entries are listed newest first regardless of insertion order`() {
        SubmittedFeedbackStore.add(context, entry(1, createdAt = 1_000L))
        SubmittedFeedbackStore.add(context, entry(2, createdAt = 3_000L))
        SubmittedFeedbackStore.add(context, entry(3, createdAt = 2_000L))

        assertEquals(
            listOf(2, 3, 1),
            SubmittedFeedbackStore.all(context).map { it.issueNumber },
        )
    }

    @Test
    fun `re-adding the same issue replaces the prior record`() {
        SubmittedFeedbackStore.add(context, entry(7, createdAt = 1_000L))
        SubmittedFeedbackStore.add(context, entry(7, createdAt = 5_000L))

        val all = SubmittedFeedbackStore.all(context)
        assertEquals(1, all.size)
        assertEquals(5_000L, all.single().createdAt)
    }
}
