MAP

Offline map features. No internet required. One person owns this folder.
Uses OSMDroid library (OpenStreetMap tiles cached locally).

Files to add here:
- MapFragment.kt          Main map view — shows signals, safe zones, volunteers as pins
- SafeZoneManager.kt      Authority creates/edits safe zones — stored in local DB
- LocationClusterer.kt    Groups signals by geographic area for authority dashboard boxes

Features:
- Offline map tiles (pre-downloaded or cached)
- Signal pins coloured by category (red=medical, orange=rescue, blue=resource)
- Safe zone overlays (authority-designated shelters, medical posts, supply points)
- Gemma 4 suggests safe zones based on signal density (calls ai/AreaSummarizer)

Rules:
- Do NOT touch MeshManager.kt or MessageRepository.kt
- Map must work with zero internet
- Layouts go in res/layout/ prefixed with fragment_map_
- Add OSMDroid dependency to app/build.gradle when starting
