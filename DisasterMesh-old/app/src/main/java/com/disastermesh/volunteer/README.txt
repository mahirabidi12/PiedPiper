VOLUNTEER

All screens and logic for the Volunteer role. One person owns this folder.

Files to add here:
- VolunteerFragment.kt    Entry point for Volunteer role
- TasksFragment.kt        Shows tasks assigned by Authority (from ResourceAllocator output)
- RespondFragment.kt      Volunteer marks a task as "Responding" or "Done"

Flow:
1. Authority generates a resource plan via ai/ResourceAllocator
2. Plan is broadcast over mesh
3. Volunteers near the assigned area receive their task
4. TasksFragment shows the task details
5. Volunteer responds → status update sent back over mesh

Rules:
- Do NOT touch MeshManager.kt or MessageRepository.kt
- Do NOT put Gemma 4 calls here — use ai/ classes
- Layouts go in res/layout/ prefixed with fragment_volunteer_
