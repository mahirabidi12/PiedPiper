MODELS

Shared data classes used across all modules.

Files to add here:
- Signal.kt          Emergency request raised by a civilian (category, priority, location, status)
- Priority.kt        Enum: CRITICAL / HIGH / NORMAL / LOW
- SignalCategory.kt  Enum: MEDICAL / RESCUE / RESOURCE / SAFETY
- AreaCluster.kt     Group of signals from the same area (used in authority dashboard)
- ResourceAllocation.kt  Gemma 4 output — how much water/food/medkits to send where
- SafeZone.kt        Authority-designated safe area shown on the map

Rules:
- These are pure data classes, no logic.
- Any change here must be agreed with the whole team since every module depends on these.
- Do NOT put Android or UI code here.
