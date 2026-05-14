# System Knowledge Specification — `offline-disaster-app`

> **Purpose of this document:** ground-truth context for building a *separate, related repository*
> (e.g. a companion native module, a LoRa relay firmware, a web peer, or a backend bridge).
> It describes **what the code actually does today**, not what the PRD aspires to. Where the two
> diverge, see [§6 Functional Gaps](#6-functional-gaps-prd-vs-implementation).
>
> Scope: `offline-disaster-app/` (active React Native + Expo app). The sibling `DisasterMesh-old/`
> Android Gradle project is legacy reference only and is **not** covered here.

---

## 1. Core Architecture

### 1.1 Decentralized, single-node design

Every install is a **complete, self-sufficient node**. There is no server tier, no shared
database, no cloud. A node bundles, in one process:

- a local SQLite store (`disaster_mesh.db`)
- a P2P transport (Google Nearby Connections, via a native bridge)
- a gossip/store-and-forward layer
- a local AI inference runtime (Gemma via MediaPipe)
- the UI (dual-role: `user` vs `admin`)

The single orchestrator is [`App.tsx`](App.tsx). Its one `useEffect` keyed on `account`
([App.tsx:68-141](App.tsx#L68-L141)) wires every subsystem together at login and tears them down at
logout. There is no router, no DI container, no API gateway — orchestration is direct function calls.

### 1.2 What replaces Client–Server REST

This app has **no REST, no HTTP client, no request/response cycle between peers**. The patterns a
REST codebase would use are replaced by three local mechanisms:

| REST concept | Replacement here | Where |
|---|---|---|
| Endpoint + JSON contract | **Protocol Contracts** — discriminated-union TypeScript types serialized to JSON over the wire | `NearbyWirePacket` ([nearbyTransport.ts:19-43](src/services/nearbyTransport.ts#L19-L43)), `ProtocolMessage` ([mesh_transfer.ts:26-31](src/services/mesh_transfer.ts#L26-L31)), `MeshPacket` ([types.ts:71-76](src/types.ts#L71-L76)) |
| Service / API layer | **Singleton service objects** with a `subscribe(listener)` / `getState()` observable pattern | `nearbyTransport`, `hubServer`, `watchdog`, `llmService`, `llmQueue` |
| Server-side compute | **Native Bridges** — `NativeModules.NearbyMesh` (P2P + file transfer) and `react-native-llm-mediapipe` (LLM) | [nearbyTransport.ts:12-17](src/services/nearbyTransport.ts#L12-L17), [mesh_transfer.ts:35-38](src/services/mesh_transfer.ts#L35-L38), [llm.ts:59-116](src/services/llm.ts#L59-L116) |
| Shared DB / ORM | One on-device SQLite file opened independently by 4 modules | see [§2](#2-data-schema-source-of-truth) |

**Key architectural consequence for a companion repo:** there is no API to call. To interoperate you
either (a) speak one of the wire Protocol Contracts in §3, or (b) implement the Native Bridge
contracts in §5. Nothing else is exposed.

### 1.3 Service-singleton pattern

Every long-lived subsystem is a class instantiated once and exported as a const
(`export const nearbyTransport = new NearbyTransport()` — [nearbyTransport.ts:366](src/services/nearbyTransport.ts#L366)).
Each exposes:

- `subscribe(listener) => unsubscribe` — pushes a full state snapshot on subscribe and on every change
- `configure(...)` — injects callbacks/dependencies (avoids import cycles)
- imperative methods (`start`, `stop`, `broadcast`, …)

`App.tsx` is the only place these are `configure`d and subscribed.

---

## 2. Data Schema (Source of Truth)

All persistence is a **single SQLite file, `disaster_mesh.db`**, opened *separately* by four modules
via `SQLite.openDatabaseSync('disaster_mesh.db')`:
[database.ts:8](src/services/database.ts#L8), [mesh_models.ts:5](src/services/mesh_models.ts#L5),
[cache.ts:6](src/db/cache.ts#L6), [chat_history.ts:6](src/db/chat_history.ts#L6).
WAL journal mode is set once in `initDatabase()` ([database.ts:40](src/services/database.ts#L40)).
Each module lazily `CREATE TABLE IF NOT EXISTS` on first use, so table ownership is by module, not central.

### 2.1 `accounts` — local identity
Defined [database.ts:41-49](src/services/database.ts#L41-L49).

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | `createId('user'|'admin')` UUID |
| `email` | TEXT UNIQUE | login key |
| `name` | TEXT | display name |
| `role` | TEXT | `'user'` or `'admin'` |
| `password_hash` | TEXT | SHA-256 of `salt:password` ([identity.ts:7-9](src/services/identity.ts#L7-L9)) |
| `salt` | TEXT | per-account salt (`createId('salt')`) |
| `created_at` | INTEGER | epoch ms |

Auth is fully offline: `findAccountByEmail` + local hash compare ([App.tsx:201-234](App.tsx#L201-L234)).

### 2.2 `app_state` — key/value node config
Defined [database.ts:50-53](src/services/database.ts#L50-L53). Known keys:
- `node_id` — stable per-device node UUID, seeded on init ([database.ts:97-100](src/services/database.ts#L97-L100), [database.ts:112-119](src/services/database.ts#L112-L119))
- `ui_locale` — persisted UI language, `en|hi|kn|te` ([useTranslation.ts:8](src/hooks/useTranslation.ts#L8))

### 2.3 `signals` — the emergency record (central domain entity)
Defined [database.ts:54-82](src/services/database.ts#L54-L82); mapped to/from the `Signal` type
([types.ts:36-62](src/types.ts#L36-L62)) by `rowToSignal` / `saveSignal`.
Columns of note: identity (`sender_node_id`, `sender_user_id`, `sender_name`, `sender_contact`),
content (`message`, `category`, `priority`, `summary`, `needs` as JSON array, `people_count`),
location (`manual_location`, `latitude`, `longitude`, `accuracy`), language/AI
(`language`, `original_text`, `original_lang`, `translated_text`, `ai_sitrep` as JSON), lifecycle
(`status`, `ttl`, `hop_count`, `target_admin_ids` JSON, `route_reason`, `created_at`, `updated_at`).

**Runtime migration:** `ensureSignalColumns()` ([database.ts:19-35](src/services/database.ts#L19-L35))
`ALTER TABLE`s in `original_text`, `original_lang`, `translated_text`, `ai_sitrep` for installs
created before the AI-enrichment schema. A companion repo writing this table must include these.

Last-write-wins merge: `mergeIncomingSignal` rejects an incoming row whose `updated_at` is `<=` the
local copy ([database.ts:208-222](src/services/database.ts#L208-L222)).

### 2.4 `seen_packets` — gossip deduplication
Defined [database.ts:83-87](src/services/database.ts#L83-L87). Columns: `id` (packet UUID, PK),
`packet_type`, `seen_at`. `hasSeenPacket` / `markPacketSeen` (`INSERT OR IGNORE`) gate the gossip
layer so a packet is forwarded at most once per node ([database.ts:244-255](src/services/database.ts#L244-L255),
consumed by [mesh.ts:5-7](src/services/mesh.ts#L5-L7)).

### 2.5 `sync_log` — human-readable event feed
Defined [database.ts:88-92](src/services/database.ts#L88-L92). `id`, `message`, `created_at`.
Append-only ring shown in the Mesh Status UI; reads are `LIMIT 12` newest
([database.ts:236-242](src/services/database.ts#L236-L242)).

### 2.6 `mesh_models` — P2P model registry (owned by `mesh_models.ts`)
Defined [mesh_models.ts:38-46](src/services/mesh_models.ts#L38-L46).

| Column | Role |
|---|---|
| `model_id` TEXT PK | e.g. `gemma-4-e2b-int4` (`PRIMARY_MODEL_ID`, [mesh_models.ts:19](src/services/mesh_models.ts#L19)) |
| `size_mb` INTEGER | advertised size (canonical seed = 1500) |
| `sha256` TEXT | expected digest; **currently a placeholder string** ([mesh_models.ts:27](src/services/mesh_models.ts#L27)) |
| `local_path` TEXT NULL | filesystem path once downloaded/verified |
| `status` TEXT | `absent \| downloading \| verifying \| ready \| error` ([mesh_models.ts:7](src/services/mesh_models.ts#L7)) |
| `error_reason` TEXT NULL | last failure detail |
| `updated_at` INTEGER | epoch ms |

A canonical row for the primary model is seeded even before the file exists, so the registry can
advertise "what a peer *could* give us" vs "what we *have*" ([mesh_models.ts:22-31, 50-55](src/services/mesh_models.ts#L22-L31)).
`hasLocalModel()` (= `status === 'ready' && localPath` set) drives the `hasAi` flag in `hello` packets.

### 2.7 `inference_cache` — LLM triage result cache (owned by `db/cache.ts`)
Defined [cache.ts:13-19](src/db/cache.ts#L13-L19).

| Column | Role |
|---|---|
| `hash` TEXT PK | SHA-256 of the normalized (`trim`, collapse whitespace, lowercase) message ([cache.ts:25-31](src/db/cache.ts#L25-L31)) |
| `json_response` TEXT | raw LLM triage JSON string |
| `hit_count` INTEGER | incremented on every cache hit |
| `created_at`, `last_used_at` | epoch ms |

Purpose: skip a ~1.5 GB-model inference when an identical distress message was already triaged
(`getCachedTriage` / `setCachedTriage`, used by [llm.ts:262-288](src/services/llm.ts#L262-L288)).

### 2.8 `chat_messages` — Help Assistant history (owned by `db/chat_history.ts`)
Defined [chat_history.ts:25-35](src/db/chat_history.ts#L25-L35). Columns: `id`, `question`, `answer`,
`scene_context`, `matched_keywords` (JSON array), `rag_topic`, `ms_taken`, `cached` (0/1),
`created_at`. Stores the offline survival-assistant Q&A log for the `HelpAssistant` screen.

---

## 3. Communication Protocols

### 3.1 Mesh wire packet contract (`NearbyWirePacket`)

Defined [nearbyTransport.ts:19-43](src/services/nearbyTransport.ts#L19-L43). Every packet is a JSON
string sent over the Nearby Connections **BYTES** payload. Discriminated on `kind`. All three carry
`nodeId`, `role`, `name`, `sentAt`.

| `kind` | Extra fields | Meaning |
|---|---|---|
| `hello` | `hasAi: boolean` | Heartbeat / presence beacon. `hasAi` piggybacks the model-availability flag so peers know who can seed Gemma. Emitted by `broadcast()` ([nearbyTransport.ts:132-139](src/services/nearbyTransport.ts#L132-L139)). |
| `signals` | `signals: Signal[]` | Store-and-forward bundle: all local signals with `hopCount < ttl` ([nearbyTransport.ts:140-147](src/services/nearbyTransport.ts#L140-L147)). |
| `mesh_protocol` | `message: ProtocolMessage` | Carrier envelope for the model-transfer state machine (§3.3). |

**Receive path** (`handleMessage`, [nearbyTransport.ts:206-297](src/services/nearbyTransport.ts#L206-L297)):
malformed/unknown `kind` or missing `nodeId` is dropped; every packet upserts a `MeshPeer`; `signals`
packets increment `hopCount`, flip `status: 'sent' → 'received'`, and `mergeIncomingSignal` each one
(last-write-wins). Admins additionally fire a notification and `enqueueTranslation`.

**Broadcast throttle:** `scheduleBroadcast()` coalesces to **one broadcast per 1500 ms**
([nearbyTransport.ts:310-319](src/services/nearbyTransport.ts#L310-L319)).

**Gossip layer** (`MeshPacket`, [types.ts:71-76](src/types.ts#L71-L76); `gossipPacket`,
[mesh.ts:4-15](src/services/mesh.ts#L4-L15)): drops a signal if `hasSeenPacket` or
`hopCount >= ttl`, else marks seen and returns a packet with `hopCount + 1`. Default `ttl = 8`,
`hopCount = 0` at creation ([App.tsx:259-261](App.tsx#L259-L261)). NB: `MeshPacket.type` allows
`'status' | 'admin_broadcast'` but only `'signal'` is produced today.

### 3.2 Hub-server WebSocket protocol (node → web dashboard, **not** peer-to-peer)

`hub_server.ts` is a hand-rolled WebSocket **server** that only **admin** nodes start
([App.tsx:118-121](App.tsx#L118-L121)). It is a one-way fan-out to the read-only web Command Center,
*not* a peer transport. Built on `react-native-tcp-socket`; performs the RFC-6455 handshake manually
(`Sec-WebSocket-Accept` = SHA-1 of key + magic GUID, [hub_server.ts:208-252](src/services/hub_server.ts#L208-L252))
and frames text manually ([hub_server.ts:260-286](src/services/hub_server.ts#L260-L286)). Default port `8080`.

Two outbound message types (JSON in a text frame):
- `{ type: 'snapshot', signals: Signal[] }` — sent once on client upgrade
- `{ type: 'sitrep', signal: Signal }` — sent on every new/updated signal (`broadcastSitrep`)

Inbound frames from clients are **ignored** ([hub_server.ts:182-186](src/services/hub_server.ts#L182-L186)) —
there is no client→hub command channel.

### 3.3 Model-transfer state machine

**Protocol messages** (`ProtocolMessage`, [mesh_transfer.ts:26-31](src/services/mesh_transfer.ts#L26-L31)),
all wrapped in a `mesh_protocol` wire packet (§3.1), sent over BYTES:

| `kind` | Direction | Payload |
|---|---|---|
| `model_announce` | seeder → all | `modelId, sha256, sizeMb, nodeId` (advisory; receiver just logs it) |
| `model_request` | receiver → seeder | `modelId, nodeId` |
| `model_reject` | seeder → receiver | `modelId, nodeId, reason` |
| `model_hash` | seeder → receiver | `modelId, sha256, sizeMb, nodeId` — sidecar sent *before* the file |
| `model_complete` | receiver → seeder | `modelId, nodeId` — releases the seeder's recipient slot |

**Happy-path sequence** (orchestrated in `handleIncomingProtocol`, [mesh_transfer.ts:209-327](src/services/mesh_transfer.ts#L209-L327);
matches PRD diagram [prd.md:832-847](prd.md#L832-L847)):

```
Receiver                                 Seeder
  | --- model_request ------------------> |   (BYTES)
  |                                       |   throttle gate: canServeModel()
  |                                       |   reserveRecipient()
  | <-- model_hash (sha256, sizeMb) ------ |   (BYTES, sidecar)
  | <======= FILE PAYLOAD ================ |   (native Payload.Type.FILE, ~1.5 GB)
  |    NearbyMeshFileProgress (n times)
  |    NearbyMeshFileComplete -> verify SHA-256 -> atomic move temp->final -> activate
  | --- model_complete ------------------> |   (BYTES) releaseRecipient()
```

**Transfer status** (`TransferStatus`, [mesh_transfer.ts:9](src/services/mesh_transfer.ts#L9)):
`idle → requesting → in_progress → verifying → complete` (or `failed` / `rejected`). State is held
in an in-memory `Map` keyed `peerId::modelId` and exposed via `subscribeTransfers`.

**Throttle / "survival etiquette"** (`mesh_throttle.ts`):
- battery floor `< 25%` → reject `low_battery` ([mesh_throttle.ts:8,36-43](src/services/mesh_throttle.ts#L8-L43))
- max **2** concurrent recipients → reject `too_many_recipients` ([mesh_throttle.ts:9,32-34](src/services/mesh_throttle.ts#L9-L34))
- thermal `serious|critical` → reject `thermal` (**hook only — `getThermalState()` always returns `unknown`**, [mesh_throttle.ts:25-29](src/services/mesh_throttle.ts#L25-L29))

**Verification & activation** (`model_verifier.ts`): `verifyDownloadedModel` checks the temp file
exists, computes SHA-256 (`FileSystem.hashFileAsync` preferred; base64 fallback only `< 50 MB`),
compares to expected — **but skips the check while the expected hash is the placeholder string**
([model_verifier.ts:63](src/services/model_verifier.ts#L63)). `atomicSwap` does delete-then-`moveAsync`;
`activateModel` sets `local_path`, status `ready`, and points `llmService` at the new path (does
**not** auto-init the model — [model_verifier.ts:88-96](src/services/model_verifier.ts#L88-L96)).

**Dev simulator:** when the native file bridge is absent, `runSimulatorTransfer` ticks fake progress
~5 % / 250 ms and drives the verifier path end-to-end so the pipeline is testable without native code
([mesh_transfer.ts:329-356](src/services/mesh_transfer.ts#L329-L356)).

---

## 4. AI & Triage Logic

### 4.1 Layered triage

Two independent layers operate on every signal:

**Layer 1 — Deterministic keyword triage (`triage.ts`), synchronous, always runs.**
`triageSignal(text, selectedCategory?)` ([triage.ts:4-52](src/services/triage.ts#L4-L52)) is pure
string matching: category inference from keyword sets, `needs` extraction, a `\d+ (people|…)`
regex for `peopleCount`, priority via critical/high keyword lists, and a script-range language
guess (`Kannada | Hindi | English`, [triage.ts:45](src/services/triage.ts#L45)). It runs inline at
signal creation ([App.tsx:240](App.tsx#L240)) — **no model, no async, no network.** This is the
baseline that guarantees the app works with zero AI installed.

**Layer 2 — Asynchronous LLM enrichment (`translator.ts` → `llm_queue.ts` → `llm.ts`).**
`enqueueTranslation(signal)` ([translator.ts:51-65](src/services/translator.ts#L51-L65)) is fired
after creation and on inbound admin signals. It:
- short-circuits if `signal.aiSitrep` already set (`needsEnrichment`, [translator.ts:31-35](src/services/translator.ts#L31-L35)) — this is the "smart broadcast" optimization: an enriched signal arriving from the mesh skips re-inference ([nearbyTransport.ts:271-285](src/services/nearbyTransport.ts#L271-L285))
- if the LLM isn't ready, parks the signal in a `pending` map and flushes when `llmService` emits `ready` ([translator.ts:24-29](src/services/translator.ts#L24-L29))
- otherwise runs `queueTriage` → `llmService.triage` → builds an `AiSitrep` ([types.ts:26-34](src/types.ts#L26-L34)) and re-`saveSignal`s with merged `priority`/`category`/`needs`/`translatedText`
- AI priority is trusted only at `confidence >= 0.7`, else the more severe of {keyword, AI} wins (`preferAiPriority`, [translator.ts:44-49](src/services/translator.ts#L44-L49))
- AI categories map back to UI categories: `medical→medical, structural→trapped, supply→resource, fire→safety` ([translator.ts:37-42](src/services/translator.ts#L37-L42))

**Queue discipline (`llm_queue.ts`):** a single LLM, two FIFO sub-queues. `triage` jobs always
drain before `assistant` jobs ([llm_queue.ts:88](src/services/llm_queue.ts#L88)). The watchdog can
`drainCancelled()` all pending jobs on low battery.

**Inference cache:** `llmService.triage` checks `inference_cache` first; a hit returns
`metrics.cached = true` with zero latency ([llm.ts:262-275](src/services/llm.ts#L262-L275)).

### 4.2 RAG for the Help Assistant

`rag.ts` does **offline keyword retrieval** (no embeddings) against
[`src/data/emergency_kb.json`](src/data/emergency_kb.json) — entries of
`{ id, topic, keywords[], instruction }`. `findInstruction(query)` tokenizes (stop-words removed),
scores entries by matched-keyword count with a prefix-match rule, returns the best
([rag.ts:45-65](src/services/rag.ts#L45-L65)). The retrieved `instruction` is injected as
"Medical Context" into the assistant prompt ([llm.ts:205-218](src/services/llm.ts#L205-L218)).

### 4.3 On-device Gemma model — filesystem & bridge requirements

**Model:** Gemma 4 E2B, int4-quantized, **~1.5 GB**, `modelId = 'gemma-4-e2b-int4'`.

**Filesystem contract:**
- Dev/hardcoded path: `HARDCODED_DEV_MODEL_PATH = '/sdcard/Download/gemma-e2b.bin'`
  ([llm.ts:6](src/services/llm.ts#L6)); also used as the P2P download `finalUri`
  ([App.tsx:85](App.tsx#L85)).
- The model file **must not** be bundled in the APK / `assets/` (would break Gradle at ~1.5 GB —
  [prd.md:765](prd.md#L765)). Acquisition is sideload (`adb push`) or P2P mesh transfer.
- `llmService.setModelPath(path)` overrides the path; `model_verifier.activateModel` calls it after a
  verified P2P download.

**Bridge contract — `react-native-llm-mediapipe`:** `resolveBridge()`
([llm.ts:71-116](src/services/llm.ts#L71-L116)) lazily `require`s the package (Android only) and
adapts it to the internal `MediaPipeLLMBridge` interface ([llm.ts:59-63](src/services/llm.ts#L59-L63)):
`init({ modelPath, maxTokens })`, `generate(prompt, { maxTokens })`, `unload()`. It tolerates two
known package APIs (`createModel`/`generate`/`releaseModel` **or** `LlmInference.create`/
`generateResponse`/`close`). `__setBridgeForTesting()` allows injecting a fake.

**Prompting:** Gemma turn tokens `<start_of_turn>user … <end_of_turn>\n<start_of_turn>model\n`
([llm.ts:194-218](src/services/llm.ts#L194-L218)). Triage uses a strict-JSON system prompt
([llm.ts:8-20](src/services/llm.ts#L8-L20)); `parseTriageJson` is defensively written to survive
markdown fences and prose drift, and accepts both current and legacy field names
([llm.ts:311-351](src/services/llm.ts#L311-L351)).

**Lifecycle / power:** `llmService` is **not** initialized eagerly — `App.tsx` warms it only after
the first mesh heartbeat and only if a local model exists and the watchdog isn't in a low-power
profile ([App.tsx:106-117](App.tsx#L106-L117)). `watchdog.ts` unloads Gemma from RAM at `≤20%`
battery and pauses model distribution at `≤10%`, with hysteresis restore thresholds
([watchdog.ts:24-29, 108-173](src/services/watchdog.ts#L24-L173)).

---

## 5. Native-to-TypeScript Interfaces

A companion repo implementing the Android side **must provide a single native module named
`NearbyMesh`** (`NativeModules.NearbyMesh`) that satisfies *both* bridge contracts below, plus the
`react-native-llm-mediapipe` package. **Note:** in this Expo *managed* project the `NearbyMesh`
native module does not yet exist — it must be authored after `expo prebuild`. Until then,
`nativeNearby` is `undefined` and the app degrades (mesh disabled, transfers use the dev simulator).

### 5.1 `NearbyMesh` — mesh transport bridge

TS-side expected shape `NearbyNativeModule` ([nearbyTransport.ts:12-17](src/services/nearbyTransport.ts#L12-L17)):

| Method | Signature | Notes |
|---|---|---|
| `start` | `(nodeName: string) => Promise<boolean>` | `nodeName` is `"<role>:<name>:<nodeId>"` ([nearbyTransport.ts:102](src/services/nearbyTransport.ts#L102)); begins advertising + discovery |
| `stop` | `() => Promise<boolean>` | |
| `send` | `(message: string) => Promise<boolean>` | broadcast a BYTES payload to all connected peers |
| `sendTo?` | `(endpointId: string, message: string) => Promise<boolean>` | **optional** — direct BYTES to one peer; used for `mesh_protocol` replies; falls back to `send` if absent ([nearbyTransport.ts:160-167](src/services/nearbyTransport.ts#L160-L167)) |

Events the TS layer subscribes to via `NativeEventEmitter` ([nearbyTransport.ts:173-196](src/services/nearbyTransport.ts#L173-L196)):

| Event | Payload (fields read) | Handling |
|---|---|---|
| `NearbyMeshPeer` | `{ endpointId, name, connected }` | upsert peer; `name` parsed as `role:name:nodeId` ([nearbyTransport.ts:348-364](src/services/nearbyTransport.ts#L348-L364)) |
| `NearbyMeshDisconnected` | `{ endpointId }` | drop peer |
| `NearbyMeshStatus` | `{ message }` | appended to `sync_log` |
| `NearbyMeshPayload` | `{ message, endpointId\|peerId\|fromEndpointId, name\|endpointName }` | JSON-parsed as a `NearbyWirePacket` |

### 5.2 `NearbyMesh` — file-transfer bridge

TS-side expected shape `NativeFileBridge` ([mesh_transfer.ts:35-38](src/services/mesh_transfer.ts#L35-L38)) —
**same `NearbyMesh` module**, additional methods:

| Method | Signature | Notes |
|---|---|---|
| `sendFile?` | `(recipientEndpointId: string, modelId: string, filePath: string) => Promise<boolean>` | send a `Payload.Type.FILE`; presence of this fn is how TS detects the real bridge vs the dev simulator ([mesh_transfer.ts:86-91](src/services/mesh_transfer.ts#L86-L91)) |
| `cancelFile?` | `(transferId: string) => Promise<boolean>` | optional; cancel an in-flight payload |

File-transfer events ([mesh_transfer.ts:108-139](src/services/mesh_transfer.ts#L108-L139)):

| Event | Payload | Handling |
|---|---|---|
| `NearbyMeshFileProgress` | `{ peerId, modelId, bytesTransferred, bytesTotal }` | updates `TransferState` to `in_progress` |
| `NearbyMeshFileComplete` | `{ peerId, modelId, tempUri, expectedSha256 }` | → `verifying`; triggers `onTransferComplete` → verify + atomic swap + activate |
| `NearbyMeshFileError` | `{ peerId, modelId, message }` | → `failed`; releases recipient slot |

Reference Kotlin skeleton for all of the above (including `onPayloadTransferUpdate` wiring) is in
[prd.md:864-912](prd.md#L864-L912).

### 5.3 `react-native-llm-mediapipe` — LLM bridge

Not a custom module — the npm package (`^0.5.0`, in `package.json`). The adapter expects one of two
API surfaces; see [§4.3](#43-on-device-gemma-model--filesystem--bridge-requirements) and
[llm.ts:80-110](src/services/llm.ts#L80-L110). Android-only (`Platform.OS !== 'android'` returns no
bridge).

---

## 6. Functional Gaps (PRD vs Implementation)

**A companion repo must NOT assume these PRD-described features exist — they are absent, partial, or
implemented differently.**

| PRD claims | Reality in code | Evidence |
|---|---|---|
| **FastAPI backend runtime** ("local APIs, WebSocket management, AI orchestration, sync endpoints" — [prd.md:356-368](prd.md#L356-L368)) | **Does not exist.** No Python, no FastAPI, no HTTP server. The only server is `hub_server.ts`, a hand-rolled raw-TCP WebSocket *broadcaster* for the web dashboard. | no Python files in repo |
| **WebSocket peer-to-peer transport** — "each node acts as WebSocket server and client" ([prd.md:284-298](prd.md#L284-L298)) | Peer transport is **Google Nearby Connections** (BYTES + FILE payloads). WebSockets are used **only** node→web-dashboard, one-way, admin-hosted. No peer↔peer WebSocket. | [nearbyTransport.ts](src/services/nearbyTransport.ts), [hub_server.ts:182-186](src/services/hub_server.ts#L182-L186) |
| **mDNS / Zeroconf discovery** ("`_disaster-mesh._tcp.local.`" — [prd.md:260-280](prd.md#L260-L280)) | **Not implemented.** Discovery is delegated entirely to the native Nearby Connections module. No mDNS/Zeroconf library. | — |
| **Web build is a "full peer"** (PRD Decision 0.3 — [prd.md:693-719](prd.md#L693-L719)) | Web entry is a **read-only dashboard** (`CommandCenter.tsx`): a WebSocket *client* to an admin hub. No WebRTC, no WebBluetooth, no `sql.js`, no mesh participation, no signal creation. | [index.ts:4-6](index.ts#L4-L6), [web/CommandCenter.tsx](src/web/CommandCenter.tsx) |
| **LoRa / ESP32 RF relays** ([prd.md:421-448](prd.md#L421-L448)) | Future roadmap only. No code, no hardware interface. | — |
| **E2E encryption, signed packets, identity verification** ([prd.md:622-627](prd.md#L622-L627)) | Not implemented. Wire packets are plaintext JSON. Passwords are salted-SHA-256 locally; that's the extent of crypto for app data. | [identity.ts](src/services/identity.ts) |
| **CRDTs / conflict resolution** ([prd.md:628-632](prd.md#L628-L632)) | Not implemented. Merge strategy is naive last-write-wins on `updated_at`. | [database.ts:208-222](src/services/database.ts#L208-L222) |
| **Voice notes** as an intake type ([prd.md:117-126](prd.md#L117-L126)) | Not implemented. Signals are text + category + location only. | [App.tsx:236-278](App.tsx#L236-L278) |
| **Ollama / GGUF / llama.cpp runtime** ([prd.md:339-353](prd.md#L339-L353)) | Runtime is **MediaPipe LLM Inference** via `react-native-llm-mediapipe`; model is a `.task`/`.bin` artifact, not GGUF. | [llm.ts](src/services/llm.ts) |
| **iOS support** (package.json has an `ios` script; PRD names iOS packages) | Effectively Android-only at runtime: Nearby Connections bridge guarded `Platform.OS !== 'android'`, MediaPipe bridge returns null off Android, throttle/battery hooks Android-oriented. | [nearbyTransport.ts:88](src/services/nearbyTransport.ts#L88), [llm.ts:73](src/services/llm.ts#L73) |
| **`expo-secure-store`** (in `app.json` plugins + `package.json`) | Imported as a dependency but **not used anywhere** in `src/`. Credentials live in the plain `accounts` SQLite table. | grep: no `expo-secure-store` import in `src/` |
| **Thermal throttling** ([prd.md:858-862](prd.md#L858-L862)) | Hook only — `getThermalState()` is hardcoded to return `'unknown'`; needs a native `PowerManager` bridge. | [mesh_throttle.ts:25-29](src/services/mesh_throttle.ts#L25-L29) |
| **Real model hash / verification** | Canonical `sha256` is the literal placeholder `PLACEHOLDER_REPLACE_WITH_REAL_GEMMA4_E2B_INT4_HASH`; the verifier **skips** the integrity check while this placeholder is in place. | [mesh_models.ts:27](src/services/mesh_models.ts#L27), [model_verifier.ts:63](src/services/model_verifier.ts#L63) |
| **The `NearbyMesh` native module itself** | Not present in this managed Expo project — must be authored after `expo prebuild`. The TS layer is fully written against it but currently runs in degraded/simulator mode. | [nearbyTransport.ts:54](src/services/nearbyTransport.ts#L54) |
| **Streaming tokens / true TTFT** | Bridge is non-streaming; `ttftMs` is reported as total `totalMs` as a worst-case proxy; token counts are `chars / 4` approximations. | [llm.ts:231-238](src/services/llm.ts#L231-L238) |
| Multilingual scope — PRD names Kannada/Hindi/Bengali ([prd.md:508-516](prd.md#L508-L516)) | UI locales are `en/hi/kn/te` (no Bengali); keyword language detection covers only Kannada/Hindi/English script ranges. Actual translation depends on the LLM being present. | [useTranslation.ts:11-16](src/hooks/useTranslation.ts#L11-L16), [triage.ts:45](src/services/triage.ts#L45) |

---

## 7. Quick Reference — Module Map

| Module | Responsibility |
|---|---|
| [App.tsx](App.tsx) | Single orchestrator: auth, subsystem wiring, signal create/status, tab nav |
| [index.ts](index.ts) | Root registration — `App` on native, `CommandCenter` on web |
| [src/services/database.ts](src/services/database.ts) | `accounts`, `app_state`, `signals`, `seen_packets`, `sync_log`; signal CRUD + dedup merge |
| [src/services/identity.ts](src/services/identity.ts) | UUID `createId`, salted SHA-256 `hashPassword` |
| [src/services/triage.ts](src/services/triage.ts) | Layer-1 deterministic keyword triage |
| [src/services/nearbyTransport.ts](src/services/nearbyTransport.ts) | Nearby Connections bridge; `NearbyWirePacket` send/receive; 1500 ms broadcast throttle |
| [src/services/mesh.ts](src/services/mesh.ts) | Gossip gate (`MeshPacket`, TTL/hop, seen-dedup) |
| [src/services/routing.ts](src/services/routing.ts) | Admin-peer target selection / queued fallback |
| [src/services/hub_server.ts](src/services/hub_server.ts) | Admin-only raw-TCP WebSocket broadcaster → web dashboard |
| [src/services/llm.ts](src/services/llm.ts) | `llmService` singleton: MediaPipe bridge, prompts, `triage()`, `parseTriageJson` |
| [src/services/llm_queue.ts](src/services/llm_queue.ts) | Single-LLM job queue, triage-before-assistant priority |
| [src/services/translator.ts](src/services/translator.ts) | Layer-2 async AI enrichment → `AiSitrep` |
| [src/services/rag.ts](src/services/rag.ts) | Offline keyword RAG over `emergency_kb.json` |
| [src/services/mesh_models.ts](src/services/mesh_models.ts) | `mesh_models` registry |
| [src/services/mesh_transfer.ts](src/services/mesh_transfer.ts) | Model-transfer state machine + dev simulator |
| [src/services/mesh_throttle.ts](src/services/mesh_throttle.ts) | Battery/concurrency/thermal "survival etiquette" gate |
| [src/services/model_verifier.ts](src/services/model_verifier.ts) | SHA-256 verify, atomic swap, model activation |
| [src/services/watchdog.ts](src/services/watchdog.ts) | Battery-driven power profiles; unload LLM / pause distribution |
| [src/services/notifications.ts](src/services/notifications.ts) | Local Android notifications |
| [src/db/cache.ts](src/db/cache.ts) | `inference_cache` table |
| [src/db/chat_history.ts](src/db/chat_history.ts) | `chat_messages` table |
| [src/hooks/useTranslation.ts](src/hooks/useTranslation.ts) | UI i18n (`en/hi/kn/te`), persisted to `app_state` |
| [src/types.ts](src/types.ts) | Shared domain & protocol types |
| [src/web/CommandCenter.tsx](src/web/CommandCenter.tsx) | Read-only web dashboard (WebSocket client) |

---

*Generated from source inspection of `offline-disaster-app/` — treat the code citations as
authoritative over the PRD wherever they conflict.*
