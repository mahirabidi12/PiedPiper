package com.disastermesh.app.ui.map

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.disastermesh.app.core.NodeIdentity
import com.disastermesh.app.R
import com.disastermesh.app.core.UserSession
import com.disastermesh.app.databinding.FragmentMapBinding
import com.disastermesh.app.db.AppDatabase
import com.disastermesh.app.db.entities.CriticalPoiEntity
import com.disastermesh.app.db.entities.SafeZoneEntity
import com.disastermesh.app.map.CriticalPoiMath
import com.disastermesh.app.model.Role
import com.disastermesh.app.model.Signal
import com.disastermesh.app.model.SignalPriority
import com.disastermesh.app.model.SignalStatus
import com.disastermesh.app.ui.AppViewModel
import com.disastermesh.app.ui.MainActivity
import com.disastermesh.app.ui.sheet.CriticalPoiBottomSheet
import com.disastermesh.app.ui.sheet.CreateSafeZoneBottomSheet
import com.disastermesh.app.ui.sheet.SignalDetailBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val appViewModel: AppViewModel by activityViewModels()

    private var dropMode = false
    private var isAuthority = false
    private var currentRole = Role.CIVILIAN
    private lateinit var localNodeId: String

    private lateinit var db: AppDatabase
    private var criticalPoiOverlayController: CriticalPoiOverlayController? = null
    private val signalOverlays = mutableListOf<Overlay>()
    private var viewDestroyed = false

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
        viewDestroyed = false
        db = AppDatabase.getInstance(requireContext())
        localNodeId = NodeIdentity.get(requireContext())

        val session = UserSession.get(requireContext())
        currentRole = session?.role ?: Role.CIVILIAN
        isAuthority = currentRole == Role.AUTHORITY
        dropMode    = (arguments?.getBoolean(ARG_DROP_MODE, false) ?: false) && isAuthority

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(20.5937, 78.9629))
        }

        criticalPoiOverlayController = CriticalPoiOverlayController(
            context = requireContext(),
            mapView = binding.mapView,
            db = db,
            scope = viewLifecycleOwner.lifecycleScope,
            onPoiTapped = { poi -> showCriticalPoiSheet(poi) }
        ).also { it.attach() }

        binding.btnDeploySafeZone.visibility = if (isAuthority) View.VISIBLE else View.GONE
        binding.btnDeploySafeZone.setOnClickListener { enableDropMode() }

        if (dropMode) enableDropMode() else disableDropMode()
        binding.btnExitDropMode.setOnClickListener { disableDropMode() }

        observeSignals()
        observeSafeZones()
        observeCriticalPoiTick()
    }

    private var mapEventsOverlay: MapEventsOverlay? = null

    private fun installMapTapListener() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!dropMode || !isAuthority) return false
                showCreateSafeZoneSheet(p.latitude, p.longitude)
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

    private fun enableDropMode() {
        if (!isAuthority || viewDestroyed || _binding == null) return
        dropMode = true
        binding.bannerDropMode.visibility = View.VISIBLE
        if (mapEventsOverlay == null) installMapTapListener()
    }

    private fun disableDropMode() {
        dropMode = false
        if (_binding != null) {
            binding.bannerDropMode.visibility = View.GONE
        }
        removeMapTapListener()
    }

    private fun showCreateSafeZoneSheet(lat: Double, lon: Double) {
        CreateSafeZoneBottomSheet { zoneName, radiusMeters ->
            createSafeZone(zoneName, lat, lon, radiusMeters)
        }.show(childFragmentManager, "create_safe_zone")
    }

    private fun createSafeZone(name: String, lat: Double, lon: Double, radiusMeters: Int) {
        val zoneId = "safe-zone-${UUID.randomUUID()}"
        val createdAt = System.currentTimeMillis()
        val service = (activity as? MainActivity)?.meshService
        if (service != null) {
            service.sendSafeZoneUpdate(
                zoneId = zoneId,
                name = name,
                latitude = lat,
                longitude = lon,
                radiusMeters = radiusMeters,
                createdAt = createdAt
            )
        } else {
            appViewModel.upsertSafeZone(
                SafeZoneEntity(
                    id = zoneId,
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radiusMeters,
                    createdAt = createdAt
                )
            )
        }
        disableDropMode()
    }

    private fun observeSignals() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.signals.collectLatest { entities ->
                    if (viewDestroyed || _binding == null) return@collectLatest
                    val signals = entities.map { it.toDomain() }
                        .filter { it.latitude != null && it.longitude != null }
                    renderSignals(signals)
                }
            }
        }
    }

    private fun observeSafeZones() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.safeZones.collectLatest {
                    if (viewDestroyed || _binding == null) return@collectLatest
                    val signals = appViewModel.signals.value
                        .map { it.toDomain() }
                        .filter { it.latitude != null && it.longitude != null }
                    renderSignals(signals)
                }
            }
        }
    }

    private fun observeCriticalPoiTick() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.criticalPoiTick.collectLatest {
                    if (viewDestroyed || _binding == null) return@collectLatest
                    criticalPoiOverlayController?.refreshVisible(force = true)
                }
            }
        }
    }

    private fun showCriticalPoiSheet(poi: CriticalPoiEntity) {
        if (viewDestroyed || _binding == null || !isAdded) return
        val user = lastKnownUserLocation()
        val distanceMeters = user?.let {
            CriticalPoiMath.haversineMeters(it.latitude, it.longitude, poi.latitude, poi.longitude)
        }
        CriticalPoiBottomSheet.newInstance(
            name = poi.name,
            amenityType = poi.amenityType,
            status = poi.operationalStatus,
            isVerified = poi.isVerified,
            canUpdate = isAuthority,
            distanceMeters = distanceMeters
        ) { selectedStatus ->
            val service = (activity as? MainActivity)?.meshService
            if (service != null) {
                service.sendCriticalPoiUpdate(
                    poiId = poi.id,
                    name = poi.name,
                    amenityType = poi.amenityType,
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    status = selectedStatus,
                    isVerified = true
                )
            } else {
                val now = System.currentTimeMillis()
                if (viewDestroyed || _binding == null) return@newInstance
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    db.criticalPoiDao().updateStatusIfNewer(
                        id = poi.id,
                        status = selectedStatus,
                        isVerified = true,
                        updatedAt = now
                    )
                }
            }
            criticalPoiOverlayController?.refreshVisible(force = true)
        }.show(childFragmentManager, "critical_poi_detail")
    }

    private fun renderSignals(signals: List<Signal>) {
        if (viewDestroyed) return
        val b = _binding ?: return

        clearSignalOverlays()

        val roleSignals = filterSignalsForRole(signals)
        val helperSignals = filterHelperSignalsForRole(signals, roleSignals)

        // IN_PROGRESS operation circles (dashed blue)
        roleSignals.filter { it.status == SignalStatus.IN_PROGRESS }.forEach { signal ->
            val lat = signal.latitude ?: return@forEach
            val lon = signal.longitude ?: return@forEach
            addSignalOverlay(buildOperationCircle(lat, lon))
        }

        // Safe zones are visible for all roles.
        appViewModel.safeZones.value.forEach { zone ->
            addSignalOverlay(buildSafeZoneCircle(zone))
        }

        // Civilian-only privacy mode: own location + no SOS/volunteer overlays.
        if (currentRole == Role.CIVILIAN) {
            renderOwnLocationIfAvailable()
            b.tvMapSignalCount.text = "SAFE MODE"
            b.mapView.invalidate()
            return
        }

        // Helper pins (role-dependent filtering).
        helperSignals.forEach { signal ->
            val lat = signal.latitude ?: return@forEach
            val lon = signal.longitude ?: return@forEach
            addSignalOverlay(buildHelperPin(b.mapView, lat, lon, signal.senderName, Color.parseColor("#3FB950")))
        }

        // SOS signal pins (role-filtered).
        roleSignals.forEach { signal ->
            val lat = signal.latitude ?: return@forEach
            val lon = signal.longitude ?: return@forEach
            val pinColor = when (signal.priority) {
                SignalPriority.CRITICAL -> Color.parseColor("#FF4D4D")
                SignalPriority.HIGH     -> Color.parseColor("#FFA033")
                SignalPriority.NORMAL   -> Color.parseColor("#FFD43B")
                SignalPriority.LOW      -> Color.parseColor("#71717A")
            }
            val marker = Marker(b.mapView).apply {
                position  = GeoPoint(lat, lon)
                title     = "${signal.category.icon} ${signal.category.label}"
                snippet   = "${signal.senderName}: ${signal.message.take(60)}"
                icon      = buildPinDrawable(pinColor, 28)
                setOnMarkerClickListener { _, _ ->
                    SignalDetailBottomSheet.newInstance(signal) { s, newStatus ->
                        (requireActivity() as MainActivity).meshService
                            ?.updateSignalStatus(s.id, newStatus)
                    }.show(childFragmentManager, "signal_detail_map")
                    true
                }
            }
            addSignalOverlay(marker)
        }

        b.tvMapSignalCount.text = "${roleSignals.size} SIGNAL${if (roleSignals.size != 1) "S" else ""} ON MAP"
        b.mapView.invalidate()

        val focus = roleSignals.firstOrNull { it.priority == SignalPriority.CRITICAL }
            ?: roleSignals.firstOrNull()
        if (focus != null) {
            b.mapView.controller.animateTo(GeoPoint(focus.latitude!!, focus.longitude!!))
        }
    }

    private fun filterSignalsForRole(allSignals: List<Signal>): List<Signal> {
        return when (currentRole) {
            Role.CIVILIAN -> emptyList()
            Role.VOLUNTEER -> allSignals.filter {
                it.assignedVolunteerId == localNodeId &&
                    it.status != SignalStatus.RESOLVED &&
                    it.status != SignalStatus.EXPIRED
            }
            Role.AUTHORITY -> allSignals
        }
    }

    private fun filterHelperSignalsForRole(allSignals: List<Signal>, roleSignals: List<Signal>): List<Signal> {
        return when (currentRole) {
            Role.CIVILIAN -> emptyList()
            Role.AUTHORITY -> allSignals.filter {
                it.senderRole == Role.VOLUNTEER && it.latitude != null && it.longitude != null
            }
            Role.VOLUNTEER -> {
                if (roleSignals.isEmpty()) return emptyList()
                allSignals
                    .asSequence()
                    .filter { it.senderRole == Role.VOLUNTEER && it.senderNodeId != localNodeId }
                    .filter { it.latitude != null && it.longitude != null }
                    .filter { helper ->
                        roleSignals.any { assigned ->
                            val aLat = assigned.latitude ?: return@any false
                            val aLon = assigned.longitude ?: return@any false
                            val hLat = helper.latitude ?: return@any false
                            val hLon = helper.longitude ?: return@any false
                            CriticalPoiMath.haversineMeters(aLat, aLon, hLat, hLon) <= VOLUNTEER_OPERATION_RADIUS_METERS
                        }
                    }
                    .toList()
            }
        }
    }

    private fun renderOwnLocationIfAvailable() {
        val b = _binding ?: return
        val location = lastKnownUserLocation() ?: return
        val marker = Marker(b.mapView).apply {
            position = GeoPoint(location.latitude, location.longitude)
            title = "You"
            snippet = "Current Location"
            icon = buildPinDrawable(Color.parseColor("#58A6FF"), 22)
        }
        addSignalOverlay(marker)
    }

    private fun addSignalOverlay(overlay: Overlay) {
        val b = _binding ?: return
        signalOverlays.add(overlay)
        b.mapView.overlays.add(overlay)
    }

    private fun clearSignalOverlays() {
        val b = _binding ?: run {
            signalOverlays.clear()
            return
        }
        if (signalOverlays.isEmpty()) return
        signalOverlays.forEach { b.mapView.overlays.remove(it) }
        signalOverlays.clear()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownUserLocation(): Location? {
        return try {
            val lm = requireContext().getSystemService(android.location.LocationManager::class.java)
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
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

    private fun buildSafeZoneCircle(zone: SafeZoneEntity): Polygon {
        val pts = circleGeoPoints(zone.latitude, zone.longitude, zone.radiusMeters.toDouble(), 40)
        return Polygon().apply {
            points = pts
            title   = zone.name
            snippet = "Safe Zone • ${zone.radiusMeters}m"
            outlinePaint.apply {
                color = Color.parseColor("#2F7DFF")
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            fillPaint.apply {
                color = Color.argb(48, 47, 125, 255)
                style = Paint.Style.FILL
            }
        }
    }

    private fun buildHelperPin(mapView: org.osmdroid.views.MapView, lat: Double, lon: Double, name: String, color: Int): Marker {
        return Marker(mapView).apply {
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
        _binding?.mapView?.onResume()
    }

    override fun onPause() {
        _binding?.mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        viewDestroyed = true

        // OSMDroid native cleanup must happen before the binding reference is released.
        val mapView = _binding?.mapView
        removeMapTapListener()

        criticalPoiOverlayController?.detach()
        criticalPoiOverlayController = null

        clearSignalOverlays()

        mapView?.onPause()
        mapView?.overlays?.clear()
        mapView?.onDetach()

        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_DROP_MODE = "drop_mode"
        private const val VOLUNTEER_OPERATION_RADIUS_METERS = 2_500.0

        fun newInstance(dropMode: Boolean = false) = MapFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_DROP_MODE, dropMode) }
        }
    }
}
