package com.disastermesh.app.ui.map

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.disastermesh.app.R
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentMapBinding
import com.disastermesh.app.model.Role
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private var dropMode = false
    private var isAuthority = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().apply {
            load(requireContext(),
                requireContext().getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            osmdroidBasePath    = requireContext().filesDir
            osmdroidTileCache   = File(requireContext().cacheDir, "tiles")
            userAgentValue      = "DisasterMesh/1.0"
        }
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dropMode    = arguments?.getBoolean(ARG_DROP_MODE, false) ?: false
        isAuthority = UserSession.get(requireContext())?.role == Role.AUTHORITY

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(20.5937, 78.9629))
        }

        if (dropMode && isAuthority) {
            binding.bannerDropMode.visibility = View.VISIBLE
            installMapTapListener()
        }
        binding.btnExitDropMode.setOnClickListener {
            dropMode = false
            binding.bannerDropMode.visibility = View.GONE
            removeMapTapListener()
        }

        observeSignals()
        observeSafeZones()
    }

    private var mapEventsOverlay: MapEventsOverlay? = null

    private fun installMapTapListener() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                showZoneTypePicker(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        }
        mapEventsOverlay = MapEventsOverlay(receiver)
        binding.mapView.overlays.add(0, mapEventsOverlay)
    }

    private fun removeMapTapListener() {
        mapEventsOverlay?.let { binding.mapView.overlays.remove(it) }
        mapEventsOverlay = null
    }

    private fun showZoneTypePicker(lat: Double, lon: Double) {
        val types = arrayOf("SHELTER", "MEDICAL POINT", "STAGING AREA", "EVACUATION ROUTE")
        AlertDialog.Builder(requireContext())
            .setTitle("Zone Type")
            .setItems(types) { _, which ->
                appViewModel.addSafeZone(lat, lon, types[which])
            }
            .show()
    }

    private fun observeSignals() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.signals.collectLatest { entities ->
                val signals = entities.map { it.toDomain() }
                    .filter { it.latitude != null && it.longitude != null }
                renderSignals(signals)
            }
        }
    }

    private fun observeSafeZones() {
        viewLifecycleOwner.lifecycleScope.launch {
            appViewModel.safeZones.collectLatest {
                val signals = appViewModel.signals.value
                    .map { it.toDomain() }
                    .filter { it.latitude != null && it.longitude != null }
                renderSignals(signals)
            }
        }
    }

    private fun renderSignals(signals: List<Signal>) {
        // Preserve the map tap overlay if drop mode is active
        val tapOverlay = mapEventsOverlay
        binding.mapView.overlays.clear()
        if (tapOverlay != null) binding.mapView.overlays.add(0, tapOverlay)

        // IN_PROGRESS operation circles (dashed blue)
        signals.filter { it.status == SignalStatus.IN_PROGRESS }.forEach { signal ->
            val lat = signal.latitude ?: return@forEach
            val lon = signal.longitude ?: return@forEach
            binding.mapView.overlays.add(buildOperationCircle(lat, lon))
        }

        // Safe zones (authority-dropped) — filled teal circles
        appViewModel.safeZones.value.forEach { zone ->
            binding.mapView.overlays.add(buildSafeZoneCircle(zone.lat, zone.lon, zone.type))
        }

        // Helper pins for VOLUNTEER and AUTHORITY senders
        signals
            .filter { it.senderRole == Role.VOLUNTEER || it.senderRole == Role.AUTHORITY }
            .forEach { signal ->
                val lat = signal.latitude ?: return@forEach
                val lon = signal.longitude ?: return@forEach
                val color = if (signal.senderRole == Role.VOLUNTEER)
                    Color.parseColor("#4DA6FF") else Color.parseColor("#B366FF")
                binding.mapView.overlays.add(buildHelperPin(lat, lon, signal.senderName, color))
            }

        // Signal pins
        signals.forEach { signal ->
            val lat = signal.latitude ?: return@forEach
            val lon = signal.longitude ?: return@forEach
            val pinColor = when (signal.priority) {
                SignalPriority.CRITICAL -> Color.parseColor("#FF4D4D")
                SignalPriority.HIGH     -> Color.parseColor("#FFA033")
                SignalPriority.NORMAL   -> Color.parseColor("#FFD43B")
                SignalPriority.LOW      -> Color.parseColor("#71717A")
            }
            val marker = Marker(binding.mapView).apply {
                position  = GeoPoint(lat, lon)
                title     = "${signal.category.icon} ${signal.category.label}"
                snippet   = "${signal.senderName}: ${signal.message.take(60)}"
                icon      = buildPinDrawable(pinColor, 28)
                setOnMarkerClickListener { _, _ ->
                    SignalDetailBottomSheet.newInstance(
                        signal = signal,
                        onAction = { s: Signal, action: SignalDetailBottomSheet.TicketAction ->
                            val svc = (requireActivity() as MainActivity).meshService
                            when (action) {
                                SignalDetailBottomSheet.TicketAction.RESOLVE -> svc?.resolveTicket(s.id)
                                SignalDetailBottomSheet.TicketAction.CANCEL  -> svc?.cancelTicket(s.id)
                                else -> svc?.updateSignalStatus(s.id, com.disastermesh.app.model.SignalStatus.ACKNOWLEDGED)
                            }
                        }
                    ).show(childFragmentManager, "signal_detail_map")
                    true
                }
            }
            binding.mapView.overlays.add(marker)
        }

        binding.tvMapSignalCount.text = "${signals.size} SIGNAL${if (signals.size != 1) "S" else ""} ON MAP"
        binding.mapView.invalidate()

        val focus = signals.firstOrNull { it.priority == SignalPriority.CRITICAL }
            ?: signals.firstOrNull()
        if (focus != null) {
            binding.mapView.controller.animateTo(GeoPoint(focus.latitude!!, focus.longitude!!))
        }
    }

    private fun buildOperationCircle(lat: Double, lon: Double): Polygon {
        val radiusMeters = 300.0
        val pts = circleGeoPoints(lat, lon, radiusMeters, 48)
        return Polygon().apply {
            points = pts
            outlinePaint.apply {
                color  = Color.parseColor("#4D9EFF")
                strokeWidth = 3f
                style  = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            fillPaint.apply {
                color = Color.argb(26, 77, 158, 255)
                style = Paint.Style.FILL
            }
        }
    }

    private fun buildSafeZoneCircle(lat: Double, lon: Double, type: String): Polygon {
        val radiusMeters = 150.0
        val pts = circleGeoPoints(lat, lon, radiusMeters, 32)
        return Polygon().apply {
            points = pts
            title   = type
            snippet = "Safe Zone"
            outlinePaint.apply {
                color = Color.parseColor("#00D4AA")
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            fillPaint.apply {
                color = Color.argb(40, 0, 212, 170)
                style = Paint.Style.FILL
            }
        }
    }

    private fun buildHelperPin(lat: Double, lon: Double, name: String, color: Int): Marker {
        return Marker(binding.mapView).apply {
            position = GeoPoint(lat, lon)
            title    = name
            snippet  = "Helper"
            icon     = buildPinDrawable(color, 20)
        }
    }

    private fun circleGeoPoints(centerLat: Double, centerLon: Double,
                                 radiusMeters: Double, points: Int): ArrayList<GeoPoint> {
        val result = ArrayList<GeoPoint>(points + 1)
        val dLat = radiusMeters / 111_000.0
        val dLon = radiusMeters / (111_000.0 * cos(Math.toRadians(centerLat)))
        for (i in 0..points) {
            val angle = 2 * Math.PI * i / points
            result.add(GeoPoint(centerLat + dLat * sin(angle), centerLon + dLon * cos(angle)))
        }
        return result
    }

    private fun buildPinDrawable(color: Int, size: Int): android.graphics.drawable.Drawable {
        return ShapeDrawable(OvalShape()).apply {
            intrinsicWidth  = size
            intrinsicHeight = size
            paint.apply {
                this.color = color
                style = Paint.Style.FILL
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_DROP_MODE = "drop_mode"

        fun newInstance(dropMode: Boolean = false) = MapFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_DROP_MODE, dropMode) }
        }
    }
}
