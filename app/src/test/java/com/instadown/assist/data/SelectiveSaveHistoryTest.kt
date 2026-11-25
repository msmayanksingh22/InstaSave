package com.instadown.assist.data

import com.instadown.assist.data.db.DownloadTransactionEvent
import com.instadown.assist.data.db.ItemEvent
import com.instadown.assist.data.db.ItemStatus
import com.instadown.assist.data.db.TransactionStatus
import com.instadown.assist.data.db.mergeWith
import com.instadown.assist.ui.ReDownloadSelectionBottomSheet.MediaItemHealth
import com.instadown.assist.ui.ReDownloadSelectionBottomSheet.VerifiedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SelectiveSaveHistoryTest {
    private fun save(index: Int, bytes: Long) = DownloadTransactionEvent(
        transactionId = "save-$index", status = TransactionStatus.SUCCESS,
        shortcode = "Carousel123", postType = "CAROUSEL", sourceUrl = "https://www.instagram.com/p/Carousel123/",
        completedCount = 1, failedCount = 0, totalBytes = bytes, sourceItemCount = 12,
        items = listOf(ItemEvent(index, "PHOTO", "content://media/$index", "slide-$index.jpg", sizeBytes = bytes))
    )

    @Test fun sequentialSparseSavesAndDuplicateRetriesKeepBothSlidesAndSizes() {
        val first = save(6, 600).mergeWith(null)
        val second = save(2, 250).mergeWith(first)
        val duplicate = save(6, 0).copy(sourceItemCount = null).mergeWith(second)
        assertEquals(listOf(2, 6), duplicate.items.map { it.itemIndex })
        assertEquals(listOf(250L, 600L), duplicate.items.map { it.sizeBytes })
        assertEquals(850L, duplicate.totalBytes)
        assertEquals(12, duplicate.totalItemCount)
        assertFalse(duplicate.isPartiallyDeleted)
        assertEquals(TransactionStatus.SUCCESS, duplicate.status)
    }

    @Test fun failedRestoreKeepsArchivedSlideAndOtherSavedSlides() {
        val first = save(2, 250).mergeWith(null)
        val archived = first.copy(items = first.items.map {
            it.copy(id = 75, mediaUri = null, status = ItemStatus.EXTERNAL_DELETED)
        })
        val withLaterSave = save(6, 600).mergeWith(archived)
        val retry = save(2, 0)
        val failed = retry.copy(
            status = TransactionStatus.FAILED,
            items = retry.items.map { it.copy(mediaUri = null, status = ItemStatus.FAILED) }
        ).mergeWith(withLaterSave)
        val missing = failed.items.first()
        assertEquals(75L, missing.id)
        assertEquals(ItemStatus.EXTERNAL_DELETED, missing.status)
        assertNull(missing.mediaUri)
        assertEquals(2, VerifiedItem(missing, MediaItemHealth.MISSING).index)
        assertEquals(TransactionStatus.PARTIAL_SUCCESS, failed.status)

        val restored = save(2, 255).mergeWith(failed)
        assertEquals(listOf(2, 6), restored.items.map { it.itemIndex })
        assertEquals(0L, restored.items.first().id)
        assertEquals(855L, restored.totalBytes)
        assertEquals(TransactionStatus.SUCCESS, restored.status)
    }
}
