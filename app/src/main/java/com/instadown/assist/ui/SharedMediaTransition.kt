package com.instadown.assist.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.instadown.assist.R

object SharedMediaTransition {
    const val EXTRA_START_POSITION = "extra_start_position"
    const val EXTRA_RETURN_POSITION = "extra_return_position"

    fun transitionNameFor(mediaId: Long): String = "instasave_media_$mediaId"

    /** Call from GalleryActivity RecyclerView item click */
    fun launchViewer(
        activity: AppCompatActivity,
        clickedThumbnail: ImageView,
        mediaId: Long,
        position: Int
    ) {
        val transitionName = transitionNameFor(mediaId)
        ViewCompat.setTransitionName(clickedThumbnail, transitionName)

        val intent = Intent(activity, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_DOWNLOAD_ID, mediaId)
            putExtra(EXTRA_START_POSITION, position)
        }
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            activity,
            clickedThumbnail,
            transitionName
        )
        activity.startActivity(intent, options.toBundle())
    }

    /** Call in GalleryActivity.onCreate() to remap return target when ViewPager2 swiped */
    fun bindGalleryExitRemapper(
        activity: AppCompatActivity,
        recyclerView: RecyclerView,
        getCurrentReturnPosition: () -> Int,
        getMediaIdAt: (Int) -> Long
    ) {
        activity.setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(
                names: MutableList<String>,
                sharedElements: MutableMap<String, View>
            ) {
                val returnPos = getCurrentReturnPosition()
                if (returnPos == RecyclerView.NO_POSITION) return
                val holder = recyclerView.findViewHolderForAdapterPosition(returnPos) ?: return
                val imageView = holder.itemView.findViewById<ImageView>(R.id.ivGalleryThumbnail)
                    ?: return
                val targetName = transitionNameFor(getMediaIdAt(returnPos))
                ViewCompat.setTransitionName(imageView, targetName)
                names.clear()
                names.add(targetName)
                sharedElements.clear()
                sharedElements[targetName] = imageView
            }
        })
    }

    /**
     * Call in MediaViewerActivity.onCreate() BEFORE setContentView/adapter init,
     * then call startPostponedEnterTransition() right after the first visible thumbnail binds.
     */
    fun bindViewerEnterRemapper(
        activity: AppCompatActivity,
        viewPager: ViewPager2,
        getMediaIdAt: (Int) -> Long,
        findActiveImageView: (Int) -> ImageView?
    ) {
        activity.supportPostponeEnterTransition()
        activity.setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(
                names: MutableList<String>,
                sharedElements: MutableMap<String, View>
            ) {
                val currentPos = viewPager.currentItem
                val activeImage = findActiveImageView(currentPos) ?: return
                val name = transitionNameFor(getMediaIdAt(currentPos))
                ViewCompat.setTransitionName(activeImage, name)
                names.clear()
                names.add(name)
                sharedElements.clear()
                sharedElements[name] = activeImage
            }
        })
    }

    /** Call inside MediaViewerActivity.finishAfterTransition() so GalleryActivity scrolls before return */
    fun setReturnPositionResult(activity: Activity, currentPosition: Int) {
        activity.setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RETURN_POSITION, currentPosition)
        )
    }
}
