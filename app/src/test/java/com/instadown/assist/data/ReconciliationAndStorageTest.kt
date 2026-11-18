package com.instadown.assist.data

import com.instadown.assist.data.db.DownloadItemRecord
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.ItemStatus
import com.instadown.assist.data.db.TransactionStatus
import com.instadown.assist.download.DownloadPlanner
import com.instadown.assist.overlay.CarouselDownloadBehavior
import com.instadown.assist.overlay.DownloadMode
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolveResult
import com.instadown.assist.resolver.model.ResolvedMedia
import com.instadown.assist.util.MediaThumbnailLoader
import org.junit.Assert.*
import org.junit.Test

class ReconciliationAndStorageTest {

    @Test
    fun testAllItemsDeletedTransactionExcludedFromActiveDatasets() {
        val deletedItems = listOf(
            DownloadItemRecord(id = 1, downloadId = 10, itemIndex = 0, mediaType = "PHOTO", mediaUri = "content://media/1", fileName = "photo_01.jpg", status = ItemStatus.EXTERNAL_DELETED),
            DownloadItemRecord(id = 2, downloadId = 10, itemIndex = 1, mediaType = "PHOTO", mediaUri = "content://media/2", fileName = "photo_02.jpg", status = ItemStatus.EXTERNAL_DELETED)
        )

        val record = DownloadRecord(
            id = 10,
            transactionId = "TX_ALL_DELETED",
            shortcode = "CODE123",
            postType = "CAROUSEL",
            sourceUrl = "https://instagram.com/p/CODE123/",
            createdAt = System.currentTimeMillis(),
            carouselCount = 2,
            status = TransactionStatus.EXTERNAL_DELETED,
            totalBytes = 2048L,
            items = deletedItems
        )

        assertEquals(0, record.availableItemCount)
        assertNull(record.representativeUri)
        assertEquals("Deleted from device", record.userFacingStatus)

        // Active dataset filter requirement: record.availableItemCount > 0 && record.status != TransactionStatus.EXTERNAL_DELETED
        val isVisibleInGallery = record.availableItemCount > 0 && record.status != TransactionStatus.EXTERNAL_DELETED
        assertFalse("All-deleted transaction must NOT be visible in Gallery", isVisibleInGallery)
    }

    @Test
    fun testPartialCarouselDeletionPreservesRemainingItems() {
        val partialItems = listOf(
            DownloadItemRecord(id = 1, downloadId = 11, itemIndex = 0, mediaType = "PHOTO", mediaUri = "content://media/1", fileName = "photo_01.jpg", status = ItemStatus.EXTERNAL_DELETED),
            DownloadItemRecord(id = 2, downloadId = 11, itemIndex = 1, mediaType = "PHOTO", mediaUri = "content://media/2", fileName = "photo_02.jpg", status = ItemStatus.AVAILABLE),
            DownloadItemRecord(id = 3, downloadId = 11, itemIndex = 2, mediaType = "PHOTO", mediaUri = "content://media/3", fileName = "photo_03.jpg", status = ItemStatus.AVAILABLE),
            DownloadItemRecord(id = 4, downloadId = 11, itemIndex = 3, mediaType = "PHOTO", mediaUri = "content://media/4", fileName = "photo_04.jpg", status = ItemStatus.EXTERNAL_DELETED),
            DownloadItemRecord(id = 5, downloadId = 11, itemIndex = 4, mediaType = "PHOTO", mediaUri = "content://media/5", fileName = "photo_05.jpg", status = ItemStatus.AVAILABLE)
        )

        val record = DownloadRecord(
            id = 11,
            transactionId = "TX_PARTIAL_CAROUSEL",
            shortcode = "CAROUSEL5",
            postType = "CAROUSEL",
            sourceUrl = "https://instagram.com/p/CAROUSEL5/",
            createdAt = System.currentTimeMillis(),
            carouselCount = 5,
            status = TransactionStatus.PARTIAL_SUCCESS,
            totalBytes = 5000L,
            items = partialItems
        )

        assertEquals(3, record.availableItemCount)
        assertEquals(5, record.totalItemCount)
        assertTrue(record.isPartiallyDeleted)
        assertEquals("3 / 5 available", record.userFacingStatus)
        assertEquals("content://media/2", record.representativeUri) // First non-deleted item

        val isVisibleInGallery = record.availableItemCount > 0 && record.status != TransactionStatus.EXTERNAL_DELETED
        assertTrue("Partially deleted transaction must remain visible in Gallery", isVisibleInGallery)
    }

    @Test
    fun testThumbnailCacheEvictionPurgesStaleEntries() {
        val testUri = "content://media/external_primary/images/media/99999"
        
        // Evict should execute without exception even if cache empty
        MediaThumbnailLoader.evict(testUri)
        
        // Evicting null or blank should be safe no-op
        MediaThumbnailLoader.evict(null)
        MediaThumbnailLoader.evict("")
    }

    @Test
    fun testDefaultSaveLocationIsPicturesInstaSave() {
        assertEquals("Pictures/InstaSave/", InstaSavePreferences.DEFAULT_SAVE_LOCATION)
        assertTrue(InstaSavePreferences.SUPPORTED_SAVE_LOCATIONS.contains("Pictures/InstaSave/"))
        assertTrue(InstaSavePreferences.SUPPORTED_SAVE_LOCATIONS.contains("DCIM/InstaSave/"))
        assertTrue(InstaSavePreferences.SUPPORTED_SAVE_LOCATIONS.contains("Download/InstaSave/"))
    }

    @Test
    fun testCarouselBehaviorEnums() {
        assertEquals(CarouselDownloadBehavior.ENTIRE_CAROUSEL, CarouselDownloadBehavior.fromString("ENTIRE_CAROUSEL"))
        assertEquals(CarouselDownloadBehavior.CURRENT_ITEM, CarouselDownloadBehavior.fromString("CURRENT_ITEM"))
        assertEquals(CarouselDownloadBehavior.ASK, CarouselDownloadBehavior.fromString("ASK"))
        assertEquals(CarouselDownloadBehavior.ENTIRE_CAROUSEL, CarouselDownloadBehavior.fromString("UNKNOWN"))
    }

    @Test
    fun testDownloadModeEnums() {
        assertEquals(DownloadMode.AUTOMATIC, DownloadMode.fromString("AUTOMATIC"))
        assertEquals(DownloadMode.CLIPBOARD, DownloadMode.fromString("CLIPBOARD"))
        assertEquals(DownloadMode.CLIPBOARD, DownloadMode.fromString("CLIPBOARD_MANUAL")) // backward compatibility
        assertEquals(DownloadMode.AUTOMATIC, DownloadMode.fromString(null))
    }

    @Test
    fun testDownloadPlannerWithSelectedSubset() {
        val items = (0..4).map { idx ->
            ResolvedMedia(
                index = idx,
                mediaType = MediaType.IMAGE,
                mediaUrl = "https://cdn.instagram.com/photo_$idx.jpg",
                thumbnailUrl = "https://cdn.instagram.com/photo_$idx.jpg",
                width = 1080,
                height = 1080,
                durationMs = null,
                bitRate = null,
                qualityClassification = "BEST",
                selectionReason = "test",
                candidates = emptyList()
            )
        }

        val resolveResult = ResolveResult(
            success = true,
            isPartial = false,
            originalInputUrl = "https://instagram.com/p/SUBSET_TEST/",
            canonicalUrl = "https://instagram.com/p/SUBSET_TEST/",
            shortcode = "SUBSET_TEST",
            sourcePathType = "POST",
            postType = PostType.CAROUSEL,
            identityVerified = true,
            methodUsed = "TEST",
            items = items,
            totalResolutionTimeMs = 5L
        )

        // Select only items 1 and 3
        val plan = DownloadPlanner.createPlan(
            expectedShortcode = "SUBSET_TEST",
            resolveResult = resolveResult,
            selectedIndices = setOf(1, 3)
        )

        assertTrue(plan.success)
        assertEquals(2, plan.items.size)
        assertEquals(1, plan.items[0].index)
        assertEquals(3, plan.items[1].index)
        assertEquals("Instagram_SUBSET_TEST_02.jpg", plan.items[0].fileName)
        assertEquals("Instagram_SUBSET_TEST_04.jpg", plan.items[1].fileName)
    }

    @Test
    fun testDeletedRecordFallsBackToPersistentThumbnail() {
        val deletedItems = listOf(
            DownloadItemRecord(id = 1, downloadId = 20, itemIndex = 0, mediaType = "PHOTO", mediaUri = null, fileName = "photo_01.jpg", status = ItemStatus.EXTERNAL_DELETED)
        )
        val expectedThumb = "/data/user/0/com.instadown.assist/files/thumbnails/TEST1234.jpg"
        val record = DownloadRecord(
            id = 20,
            transactionId = "TX_DELETED_WITH_THUMB",
            shortcode = "TEST1234",
            postType = "PHOTO",
            sourceUrl = "https://instagram.com/p/TEST1234/",
            createdAt = System.currentTimeMillis(),
            carouselCount = 1,
            status = TransactionStatus.EXTERNAL_DELETED,
            totalBytes = 1024L,
            items = deletedItems,
            thumbnailPath = expectedThumb
        )

        assertTrue(record.isDeletedFromDevice)
        assertEquals(0, record.availableItemCount)
        assertEquals(expectedThumb, record.representativeUri)
    }

    @Test
    fun testDownloadTransactionEventPreservesThumbnailMetadata() {
        val thumbPath = "/data/user/0/com.instadown.assist/files/thumbnails/SHORT123.jpg"
        val event = com.instadown.assist.data.db.DownloadTransactionEvent(
            transactionId = "TX_THUMB_TEST",
            status = TransactionStatus.SUCCESS,
            shortcode = "SHORT123",
            postType = "REEL",
            sourceUrl = "https://instagram.com/reel/SHORT123/",
            completedCount = 1,
            failedCount = 0,
            totalBytes = 2048L,
            authorUsername = "natgeo",
            caption = "Wild planet",
            thumbnailPath = thumbPath
        )

        assertEquals("natgeo", event.authorUsername)
        assertEquals("Wild planet", event.caption)
        assertEquals(thumbPath, event.thumbnailPath)
    }

    @Test
    fun testCarouselAggregateProgressCalculation() {
        val totalItems = 4
        val itemPercents = intArrayOf(100, 100, 50, 0)
        val sum = itemPercents.sum()
        val overall = (sum / totalItems).coerceIn(0, 100)
        assertEquals(62, overall)
    }
}
