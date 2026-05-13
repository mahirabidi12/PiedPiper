CIVILIAN

All screens and logic for the Civilian (USER) role. One person owns this folder.

Files to add here:
- CivilianFragment.kt    Main civilian screen — entry point after login for USER role
- SignalFormFragment.kt  Form to raise an emergency signal (text/voice, location, category chips)
- MySignalsFragment.kt   Shows the civilian's own submitted signals and their live status

Flow:
1. Civilian types or speaks their emergency
2. ai/Translator.kt converts regional language to English if needed
3. ai/SignalClassifier.kt assigns category + priority
4. Signal is sent over the mesh via MeshManager (do not call MeshManager directly — use a ViewModel or callback)
5. Signal is saved to db/SignalDao

Rules:
- Do NOT touch MeshManager.kt or MessageRepository.kt
- Do NOT put Gemma 4 calls here — use ai/ classes
- Layouts go in res/layout/ prefixed with fragment_civilian_
