package com.disastermesh.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.disastermesh.app.R
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.CriticalPoiAmenity
import com.disastermesh.app.db.entities.CriticalPoiEntity
import com.disastermesh.app.db.entities.CriticalPoiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Lightweight viewport-bound POI overlay.
 *
 * Design goals:
 * - Query only the visible map bounds.
 * - Reuse marker instances and icon drawables.
 * - Keep memory stable on low-RAM devices.
 */
class CriticalPoiOverlayController(
    private val context: Context,
    private val mapView: MapView,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val onPoiTapped: (CriticalPoiEntity) -> Unit
) : MapListener {

    private val folderOverlay = FolderOverlay()
    private val markerById = LinkedHashMap<String, Marker>(256)
    private val iconCache = HashMap<String, Drawable>(24)

    private var refreshJob: Job? = null
    private var lastBoundsKey: String? = null
    private var detached = false

    fun attach() {
        detached = false
        if (!mapView.overlays.contains(folderOverlay)) {
            mapView.overlays.add(folderOverlay)
        }
        mapView.addMapListener(this)
        refreshVisible(force = true)
    }

    fun detach() {
        if (detached) return
        detached = true
        refreshJob?.cancel()
        refreshJob = null
        mapView.removeMapListener(this)
        markerById.values.forEach {
            it.closeInfoWindow()
            it.setOnMarkerClickListener(null)
        }
        mapView.overlays.remove(folderOverlay)
        markerById.clear()
        iconCache.clear()
        lastBoundsKey = null
    }

    override fun onScroll(event: ScrollEvent?): Boolean {
        scheduleRefresh(force = false)
        return false
    }

    override fun onZoom(event: ZoomEvent?): Boolean {
        scheduleRefresh(force = true)
        return false
    }

    fun refreshVisible(force: Boolean) {
        scheduleRefresh(force)
    }

    private fun scheduleRefresh(force: Boolean) {
        if (detached) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(120)
            if (!isActive || detached) return@launch
            val box = mapView.boundingBox ?: return@launch
            val key = buildBoundsKey(box, mapView.zoomLevelDouble)
            if (!force && key == lastBoundsKey) return@launch
            lastBoundsKey = key

            val rows = withContext(Dispatchers.IO) {
                queryVisible(box)
            }
            if (!isActive || detached) return@launch
            render(rows)
        }
    }

    private suspend fun queryVisible(box: BoundingBox): List<CriticalPoiEntity> {
        val north = maxOf(box.latNorth, box.latSouth)
        val south = minOf(box.latNorth, box.latSouth)
        val west = box.lonWest
        val east = box.lonEast
        val limit = 320
        return if (west <= east) {
            db.criticalPoiDao().getInBounds(
                south = south,
                north = north,
                west = west,
                east = east,
                limit = limit
            )
        } else {
            db.criticalPoiDao().getInBoundsAcrossDateLine(
                south = south,
                north = north,
                west = west,
                east = east,
                limit = limit
            )
        }
    }

    private fun render(items: List<CriticalPoiEntity>) {
        if (detached) return
        val visibleIds = items.asSequence().map { it.id }.toHashSet()

        val stale = markerById.keys.filter { it !in visibleIds }
        stale.forEach { id ->
            val marker = markerById.remove(id) ?: return@forEach
            folderOverlay.remove(marker)
        }

        items.forEach { poi ->
            val marker = markerById[poi.id] ?: createMarker(poi).also {
                markerById[poi.id] = it
                folderOverlay.add(it)
            }
            marker.position = GeoPoint(poi.latitude, poi.longitude)
            marker.title = poi.name
            marker.snippet = "${poi.amenityType} • ${poi.operationalStatus}"
            marker.icon = iconFor(poi)
        }

        mapView.invalidate()
    }

    private fun createMarker(poi: CriticalPoiEntity): Marker = Marker(mapView).apply {
        position = GeoPoint(poi.latitude, poi.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = poi.name
        snippet = "${poi.amenityType} • ${poi.operationalStatus}"
        icon = iconFor(poi)
        setOnMarkerClickListener { _, _ ->
            onPoiTapped(poi)
            true
        }
    }

    private fun iconFor(poi: CriticalPoiEntity): Drawable {
        val key = "${poi.amenityType}:${poi.operationalStatus}:${poi.isVerified}"
        return iconCache.getOrPut(key) {
            val symbol = symbolForAmenity(poi.amenityType)
            val color = colorFor(poi.amenityType, poi.operationalStatus)
            createTacticalIcon(symbol, color, poi.isVerified)
        }
    }

    private fun symbolForAmenity(amenityType: String): String = when (amenityType) {
        CriticalPoiAmenity.HOSPITAL,
        CriticalPoiAmenity.CLINIC,
        CriticalPoiAmenity.PHARMACY,
        CriticalPoiAmenity.BLOOD_BANK -> "✚"
        CriticalPoiAmenity.FIRE_STATION -> "▲"
        CriticalPoiAmenity.POLICE -> "⬟"
        else -> "•"
    }

    private fun colorFor(amenityType: String, status: String): Int {
        return when (status) {
            CriticalPoiStatus.FLOODED -> Color.parseColor("#3A99D8")
            CriticalPoiStatus.OUT_OF_MEDICAL_SUPPLIES -> context.getColor(R.color.priority_high)
            CriticalPoiStatus.TEMP_TRIAGE_CAMP -> context.getColor(R.color.volunteer)
            else -> when (amenityType) {
                CriticalPoiAmenity.HOSPITAL -> context.getColor(R.color.priority_critical)
                CriticalPoiAmenity.CLINIC -> Color.parseColor("#FF7A66")
                CriticalPoiAmenity.PHARMACY -> context.getColor(R.color.volunteer)
                CriticalPoiAmenity.BLOOD_BANK -> Color.parseColor("#C62A2A")
                CriticalPoiAmenity.FIRE_STATION -> Color.parseColor("#F78166")
                CriticalPoiAmenity.POLICE -> context.getColor(R.color.civilian)
                else -> context.getColor(R.color.text_muted)
            }
        }
    }

    private fun createTacticalIcon(symbol: String, color: Int, isVerified: Boolean): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (36f * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = if (isVerified) 255 else 190
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#12151D")
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val center = sizePx / 2f
        val radius = sizePx / 2f - (2f * density)
        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius, strokePaint)
        canvas.drawText(symbol, center, center + (5f * density), textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun buildBoundsKey(box: BoundingBox, zoom: Double): String {
        // Quantized key avoids re-query churn from tiny map jitter.
        fun q(value: Double) = "%.3f".format(value)
        return "${q(box.latNorth)}:${q(box.latSouth)}:${q(box.lonWest)}:${q(box.lonEast)}:${zoom.toInt()}"
    }
}
