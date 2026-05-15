package com.disastermesh

object AppConstants {
    // Mesh
    const val MESH_SERVICE_ID = "com.disastermesh.mesh"
    const val PEER_STATUS_PREFIX = "MESH"

    // Network timeouts
    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 30_000

    // Download
    const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    const val DOWNLOAD_UI_THROTTLE_MS = 400L

    // Signal classification
    const val SIGNAL_MAX_TAGS = 4
    const val SIGNAL_SUMMARY_MAX_CHARS = 80
    const val SIGNAL_TEXT_PREVIEW_CHARS = 60

    // Location clustering
    const val CLUSTER_RADIUS_METRES = 500.0
    const val EARTH_RADIUS_METRES = 6_371_000.0

    // UI fallbacks (used in non-Context classes)
    const val LOCATION_UNKNOWN_LABEL = "Unknown location"

    // Permissions request codes
    const val REQUEST_PERMISSIONS_MAIN = 1001
    const val REQUEST_PERMISSIONS_CIVILIAN = 2001
    const val REQUEST_PERMISSIONS_AUTHORITY = 3001

    // Target role values
    const val TARGET_ALL = "ALL"
    const val TARGET_VOLUNTEER = "VOLUNTEER"
    const val TARGET_AUTHORITY = "AUTHORITY"
}
