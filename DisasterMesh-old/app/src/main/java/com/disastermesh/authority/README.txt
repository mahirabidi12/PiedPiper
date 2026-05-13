AUTHORITY

All screens and logic for the Authority role. One person owns this folder.

Files to add here:
- AuthorityFragment.kt       Entry point for Authority role
- DashboardFragment.kt       Area cluster overview — one box per area showing signal counts
- AreaDetailFragment.kt      Drill-down view — medical/rescue/resource breakdown for one area
- SignalDetailFragment.kt    Full detail of a single signal
- ResourcePlanFragment.kt    Gemma 4 resource allocation output — water/food/medkits/volunteers per area

Flow:
1. Incoming signals arrive via mesh and are saved to DB
2. map/LocationClusterer groups them by area
3. DashboardFragment shows one card per area with counts
4. Tapping an area opens AreaDetailFragment
5. Tapping "Generate Plan" calls ai/ResourceAllocator
6. ResourcePlanFragment shows the allocation and assigns volunteers

Rules:
- Do NOT touch MeshManager.kt or MessageRepository.kt
- Do NOT put Gemma 4 calls here — use ai/ classes
- Layouts go in res/layout/ prefixed with fragment_authority_
