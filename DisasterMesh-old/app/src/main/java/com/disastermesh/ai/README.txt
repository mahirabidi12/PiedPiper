AI — Gemma 4 Integration

All Gemma 4 features live here. One person owns this entire folder.
No other module talks to Gemma directly — they call these classes only.

Files to add here:
- GemmaClient.kt        Single interface to the Gemma 4 model (LiteRT on-device)
- SignalClassifier.kt   Takes civilian text → returns category + priority + extracted details
- Translator.kt         Regional language input → English (and English → regional for responses)
- ResourceAllocator.kt  Takes area signals → returns optimal water/food/medkit/volunteer plan
- AreaSummarizer.kt     Takes list of signals → returns human-readable area situation report
- PromptTemplates.kt    All prompt strings in one place, easy to tune

Rules:
- Only GemmaClient.kt talks to the model directly.
- All other files in this folder call GemmaClient.
- No UI code here.
- No mesh/P2P code here.
