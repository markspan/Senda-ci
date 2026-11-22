package io.github.markspan.sensorium.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.github.markspan.sensorium.BuildConfig
import io.github.markspan.sensorium.stream.PreviewBuffer
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Interactive OpenStreetMap view of the GPS stream: the track so far, the latest position and
 * its accuracy circle. The map follows the position until the user pans or zooms it;
 * [follow] (the card's Follow button) centres it again.
 */
@SuppressLint("ClickableViewAccessibility") // the touch listener only observes; the map handles the touch
class GpsMap(private val map: MapView, private val track: PreviewBuffer, private val followButton: View) {

    private val scratch = FloatArray(track.channels * track.capacity)
    private var following = true
    private var zoomedIn = false
    private val density = map.resources.displayMetrics.density
    private val primary = MaterialColors.getColor(map, com.google.android.material.R.attr.colorPrimary)

    private val trackLine = Polyline(map).apply {
        outlinePaint.color = primary
        outlinePaint.strokeWidth = 4f * density
        isEnabled = false
    }
    private val accuracy = Polygon(map).apply {
        fillPaint.color = ColorUtils.setAlphaComponent(primary, 0x30)
        outlinePaint.color = ColorUtils.setAlphaComponent(primary, 0x80)
        outlinePaint.strokeWidth = 1f * density
        isEnabled = false
    }
    private val position = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        setInfoWindow(null)
        isEnabled = false
    }

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(3.0)
        map.overlays.addAll(listOf(accuracy, trackLine, position, CopyrightOverlay(map.context)))
        map.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Inside the scrolling page: keep the drag for the map, and stop following.
                v.parent.requestDisallowInterceptTouchEvent(true)
                setFollowing(false)
            }
            false
        }
        followButton.setOnClickListener { follow() }
        setFollowing(true)
    }

    /** Centres the map on the latest position and keeps it there as new fixes come in. */
    fun follow() {
        setFollowing(true)
        update()
    }

    /** Redraws the track from the GPS buffer; call when a new fix has arrived. */
    fun update() {
        val ch = track.channels
        val n = track.snapshot(scratch)
        if (n == 0) {
            listOf(trackLine, accuracy, position).forEach { it.isEnabled = false }
            map.invalidate()
            return
        }
        val points = (0 until n).map { GeoPoint(scratch[it * ch].toDouble(), scratch[it * ch + 1].toDouble()) }
        val last = points.last()
        trackLine.setPoints(points)
        trackLine.isEnabled = n > 1
        position.position = last
        position.isEnabled = true
        val radius = scratch[(n - 1) * ch + 2]
        accuracy.isEnabled = !radius.isNaN() && radius > 0
        if (accuracy.isEnabled) accuracy.points = Polygon.pointsAsCircle(last, radius.toDouble())
        if (following) {
            if (!zoomedIn) {
                map.controller.setZoom(FIX_ZOOM)
                map.controller.setCenter(last)
                zoomedIn = true
            } else {
                map.controller.animateTo(last)
            }
        }
        map.invalidate()
    }

    /** Forgets the zoom level, so the next streaming session zooms in on its first fix again. */
    fun reset() {
        zoomedIn = false
        setFollowing(true)
        update()
    }

    fun onResume() = map.onResume()

    fun onPause() = map.onPause()

    private fun setFollowing(on: Boolean) {
        following = on
        followButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    companion object {
        private const val FIX_ZOOM = 17.0

        /**
         * osmdroid settings, before the first MapView is inflated: identify the app to the
         * OpenStreetMap tile servers (their usage policy requires it) and keep the tile cache in
         * the app's own cache directory, which needs no storage permission.
         */
        fun configure(context: Context) {
            Configuration.getInstance().apply {
                userAgentValue = BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME
                osmdroidBasePath = File(context.cacheDir, "osmdroid")
                osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            }
        }
    }
}
