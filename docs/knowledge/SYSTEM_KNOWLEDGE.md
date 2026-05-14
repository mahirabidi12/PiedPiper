# DisasterMesh — System Knowledge & Design

> **Status:** unified source of truth. This document **supersedes and consolidates**
> [`SYSTEM_ARCHITECTURE.md`](./SYSTEM_ARCHITECTURE.md) (what the native Android code does today) and
> [`SYSTEM_KNOWLEDGE_SPEC.md`](./SYSTEM_KNOWLEDGE_SPEC.md) (the React Native rewrite on the
> `disaster-mesh-new` branch). Where the two disagreed, the **native Android architecture wins** —
> that is the codebase this project builds on.
>
> Every section is tagged so intent is never confused with reality:
> **✅ Built** = present in [`DisasterMesh-old/`](../../DisasterMesh-old/) on `main` ·
> **🔧 Planned** = designed here, not yet coded ·
> **💡 Borrowed** = concept lifted from the RN spec, re-targeted to native Android.

---

## 1. Product vision

DisasterMesh is an **offline, infrastructure-free disaster-relief app for Android**. When the
cellular and internet infrastructure is down (earthquake, flood, cyclone), phones running the app
**form a peer-to-peer mesh** over Bluetooth / Wi-Fi Direct and relay information hop-by-hop. There is
**no server and no internet** — every phone is a node, a relay, and a complete copy of the system.

Three things make it more than a chat app:

1. **Structured emergency signals.** Civilians don't just send text — they raise a *Signal* with a
   category, priority, location, and headcount. The mesh carries these to everyone in range and
   beyond, hop by hop.
2. **Authorities and volunteers can act.** Authorities and volunteers respond to signals
   (acknowledge, assign, mark en-route, resolve), designate safe zones, and push resource plans and
   tasks back across the same mesh.
3. **Gemma runs locally, on-device.** A quantized Gemma model — executed via **LiteRT (Google AI
   Edge)** — adds intelligence with zero connectivity: triaging signals, **converting regional
   languages** to a common one and back, answering civilians' survival questions ("how do I treat a
   burn?"), summarizing an area's situation for authorities, and proposing resource allocation.

The guiding constraint: **the app must work fully with zero infrastructure and zero AI installed.**
AI is an enhancement layer; the mesh and the deterministic logic underneath it are the guarantee.

---

## 2. Locked design decisions

These were decided for this unified design and drive everything below.

| Decision | Choice | Rationale |
|---|---|---|
| **Base platform** | **Native Android (Kotlin)** — extend [`DisasterMesh-old/`](../../DisasterMesh-old/) | Matches the existing working mesh code, best fit for Nearby Connections + LiteRT, and the `ai/ map/ civilian/ …` package scaffolding is already laid out for it. |
| **On-device LLM runtime** | **LiteRT / Google AI Edge** (MediaPipe LLM Inference) | Google's official on-device runtime, first-class Gemma support, native Kotlin API, `.task` model format. Already named in [`ai/README.txt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/ai/README.txt). |
| **Mesh propagation** | **TTL + hop-count gossip** (bounded flooding) + UUID dedup | Caps relay traffic in a large/dense mesh; current UUID-only flood reaches every node forever. |
| **Model delivery** | **P2P transfer over the mesh + manual sideload** | A field deployment can't pre-load every phone; one device with the model seeds its neighbours over Nearby Connections FILE payloads, with hash verification and battery throttling. |

### 2.1 V1 scope (current build target) 🎯

**V1 keeps decisions 1 & 2 and deliberately defers decisions 3 & 4.** The goal of V1 is to add
on-device Gemma to the *existing, working* mesh chat **without changing the mesh at all**.

| Decision | V1 | Deferred to V2+ |
|---|---|---|
| 1 — Base platform | ✅ Native Android Kotlin | — |
| 2 — On-device LLM | ✅ LiteRT + Gemma, **running locally on each device** | — |
| 3 — Mesh propagation | ⏸ **Keep the current "forever flood"** — UUID-only dedup, no TTL, no hop count. `MeshManager` / `Message` are untouched. | TTL + hop-count `GossipRouter` (§5.2) |
| 4 — Model delivery | ✅ **In-app HTTPS download** (CODM/PUBG-style: prompt + progress bar, resumable, done once *pre-disaster*) **+ manual sideload** as a fallback. No mesh transfer. | P2P FILE transfer over the mesh + `mesh_models` registry (§9) |

The model is **Gemma 3n E2B** (int4) — the model the older project docs call "Gemma 4 E2B".
There is no "Gemma 4"; the Gemma family is 1 → 2 → 3 → 3n, and "E2B" is a Gemma 3n variant.

**What V1 adds (all additive — no existing file's behaviour changes):**
- `ai/GemmaClient.kt` — the single LiteRT bridge; the *only* class that touches the model.
- `ai/PromptTemplates.kt` — Gemma chat-format prompt strings.
- `ai/ModelDownloader.kt` — in-app, resumable HTTPS download of the Gemma `.task` file, with a
  progress bar. Writes to `<model>.task.part` then renames into place; supports HTTP Range resume.
  The download URL (`MODEL_URL`) must point at a **self-hosted** copy of the `.task` file —
  Gemma is licence-gated on Hugging Face / Kaggle and cannot be hot-linked.
- `AssistantActivity` — a self-contained Help-Assistant screen: ask a question → Gemma answers
  offline; shows the download panel when the model is missing. Reachable from a new button in
  `MainActivity`'s header.
- `app/build.gradle` — the `tasks-genai` dependency; `minSdk` 23 → 24 (LiteRT requirement).
- `AndroidManifest.xml` — `largeHeap`, the new activity registration, and `INTERNET` /
  `ACCESS_NETWORK_STATE` (used **only** for the one-time pre-disaster model download — the mesh
  itself never needs internet).

**What V1 explicitly does NOT touch:** `MeshManager`, `MessageRepository`, `Message`, `Role`,
`UserSession`, `db/`, `MessageAdapter`. Chat keeps working exactly as before; if the model file is
absent the app behaves identically to today.

Sections [§5.2](#52-bounded-gossip-routing--planned) and [§9](#9-model-distribution--p2p-transfer--sideload--planned)
are therefore **post-V1** — see the ⏸ notes on each.

---

## 3. Tech stack

| Layer | Choice | Status |
|---|---|---|
| Language / build | **Kotlin 1.9.22**, Gradle 8.6, AGP 8.2.2, `kotlin-kapt` | ✅ Built |
| Android target | `minSdk 23`, `compileSdk/targetSdk 34` | ✅ Built |
| UI | Classic Android Views + XML layouts; **migrate to Fragments** for role screens | ✅ / 🔧 Planned |
| P2P transport | **Google Nearby Connections** (`play-services-nearby 19.1.0`), `Strategy.P2P_CLUSTER` | ✅ Built |
| Persistence | **Room 2.6.1** over SQLite (`disaster_mesh.db`) | ✅ Built (1 table) → 🔧 Planned (full schema, §6) |
| Wire format | JSON (`org.json`) over Nearby **BYTES**; **FILE** payloads for model transfer | ✅ / 🔧 Planned |
| On-device AI | **LiteRT / Google AI Edge** running quantized **Gemma 3n E2B** (`.task`, int4) | 🔧 Planned |
| Offline maps | **OSMDroid** (cached OpenStreetMap tiles) | 🔧 Planned |
| Local identity | `SharedPreferences` session → `accounts` table | ✅ → 🔧 Planned |
| Async | Add **Kotlin Coroutines** (current code uses `allowMainThreadQueries()`) | 🔧 Planned |

---

## 4. High-level architecture

A single Android process. No server tier, no router, no DI container — subsystems are direct
objects wired together at startup.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Android process  com.disastermesh                                        │
│                                                                            │
│  ┌────────────┐   PRESENTATION                                            │
│  │ RoleSetup  │   role-specific Fragments:                                │
│  │ Activity   │   civilian/  volunteer/  authority/  map/                 │
│  └────────────┘                  │                                        │
│         │                        ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ DOMAIN & ORCHESTRATION                                              │ │
│  │  UserSession · SignalService · ResponseService · SafeZoneService    │ │
│  │  TaskService · ResourcePlanService                                  │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│      │                  │                    │                 │          │
│      ▼                  ▼                    ▼                 ▼          │
│  ┌────────┐   ┌──────────────────┐   ┌───────────────┐  ┌──────────────┐ │
│  │  AI    │   │  MESH            │   │ PERSISTENCE   │  │  POWER       │ │
│  │ Gemma  │   │  MeshManager     │   │ Room /        │  │ Battery      │ │
│  │ (LiteRT)│  │  GossipRouter    │   │ disaster_mesh │  │ Watchdog     │ │
│  │ triage │   │  ModelTransfer   │   │   .db (§6)    │  │ Throttle     │ │
│  │ xlate  │   │                  │   │               │  │              │ │
│  │ help   │   │  Nearby Conns    │   │               │  │              │ │
│  │ summary│   │  (BYTES + FILE)  │   │               │  │              │ │
│  └────────┘   └──────────────────┘   └───────────────┘  └──────────────┘ │
└────────────────────────────│──────────────────────────────────────────────┘
                             │  Bluetooth / BLE / Wi-Fi Direct / hotspot
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌──────────┐   ┌──────────┐   ┌──────────┐
        │ Peer node│◄─►│ Peer node│◄─►│ Peer node│   … mesh cluster
        └──────────┘   └──────────┘   └──────────┘
```

**Five layers:**

| Layer | Responsibility | Key modules |
|---|---|---|
| **Presentation** | Role-specific screens (civilian intake, volunteer tasks, authority dashboard, map) | `civilian/`, `volunteer/`, `authority/`, `map/` |
| **Domain** | Business logic over the data model — create signals, record responses, manage lifecycle | `*Service` classes, `model/` data classes |
| **Mesh** | P2P transport, bounded gossip routing, model file transfer | `mesh/`, `meshmodels/` |
| **Persistence** | Single SQLite DB; last-write-wins merge of incoming records | `db/` |
| **AI** | Gemma via LiteRT — triage, translation, help assistant, summaries, allocation | `ai/` |
| **Power** | Battery-driven throttling of the LLM and model distribution | `power/` |

---

## 5. The mesh layer

### 5.1 Transport ✅ Built

Google Nearby Connections, `Strategy.P2P_CLUSTER` (many-to-many mesh), `SERVICE_ID =
"com.disastermesh.mesh"`. Each device **advertises and discovers simultaneously**; connections are
**auto-accepted** (no manual auth — appropriate for an open emergency mesh). The OS picks the
physical medium (Bluetooth / BLE / Wi-Fi Direct / hotspot) automatically; effective single-hop range
is tens of metres, extended arbitrarily by multi-hop relaying. Implemented today in
[`MeshManager.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt).

### 5.2 Bounded gossip routing 🔧 Planned · ⏸ Post-V1

> **⏸ Deferred past V1.** V1 keeps the current "forever flood" (UUID-only dedup, no TTL) and leaves
> `MeshManager` untouched — see [§2.1](#21-v1-scope-current-build-target-). The `GossipRouter` below
> is the V2 target.

Today's code floods every message to the entire connected component, stopped only by per-node
seen-ID dedup — every message lives in every node forever. The unified design adds a **`GossipRouter`**
that wraps `MeshManager` with **TTL + hop-count**:

```
on receive packet P from peer X:
   if seen_packets has P.id            → drop (duplicate)
   if P.hopCount >= P.ttl              → drop (expired)
   markPacketSeen(P.id)
   persist P.payload   (last-write-wins merge on updated_at)
   render P            (if visible to my role)
   if P.hopCount + 1 < P.ttl:
       P.hopCount += 1
       broadcast P to all peers except X
```

- Default `ttl = 8`, `hopCount = 0` at creation.
- UUID dedup (`seen_packets` table) remains the loop-breaker; TTL caps the *radius* and the
  *traffic*.
- **Store-and-forward** is kept: on a new peer connection, send recent un-expired records so a phone
  that was off during the event still receives the backlog. The peer's dedup drops what it has.

### 5.3 Wire protocol — the unified `MeshPacket` 🔧 Planned 💡 Borrowed

Every mesh transmission is one JSON object over a Nearby **BYTES** payload. It is a discriminated
union on `type`:

```json
{
  "id":           "<packet UUID>",        // dedup key
  "type":         "signal | signal_update | safe_zone | resource_plan | task | hello | mesh_protocol",
  "ttl":          8,
  "hopCount":     0,
  "originNodeId": "<stable node UUID>",
  "originRole":   "CIVILIAN | VOLUNTEER | AUTHORITY",
  "originName":   "Officer Singh",
  "sentAt":       1715600000000,
  "payload":      { ... }                  // shape determined by `type`
}
```

| `type` | `payload` | Direction | Meaning |
|---|---|---|---|
| `hello` | `{ hasAi, modelId, batteryPct }` | all → all | Presence beacon; `hasAi` advertises who can seed the Gemma model. |
| `signal` | a `Signal` row (§6.3) | civilian → all | A new or updated emergency signal. |
| `signal_update` | a `SignalUpdate` row (§6.4) | volunteer/authority → all | A response: acknowledge, assign, en-route, resolve, note. |
| `safe_zone` | a `SafeZone` row (§6.6) | authority → all | A designated shelter / medical post / supply point. |
| `resource_plan` | a `ResourcePlan` row (§6.7) | authority/AI → all | An allocation plan for an area. |
| `task` | a `Task` row (§6.8) | authority → all | A unit of work assigned to a volunteer node. |
| `mesh_protocol` | a model-transfer message (§8.2) | seeder ↔ receiver | Carrier envelope for the model-transfer state machine. |

Beyond BYTES, the mesh also carries **FILE** payloads — used only for the ~1–2 GB Gemma model file
(§8).

---

## 6. Data schema (proposed) 🔧 Planned

All persistence is **one SQLite file, `disaster_mesh.db`**, managed by Room. Today it holds a single
`messages` table ✅; the unified design replaces `messages` with a structured `signals` table and
adds the rest. **Every mesh-replicated table carries `ttl` / `hop_count` and merges last-write-wins
on `updated_at`.**

```
              ┌─────────────┐
              │  app_state  │  node_id, ui_locale, schema_version
              └─────────────┘
   ┌──────────┐                       ┌──────────────┐
   │ accounts │── node_id ───────────►│    peers     │  known mesh nodes
   └────┬─────┘                       └──────────────┘
        │ id
        ▼ sender_account_id                       ┌──────────────────┐
   ┌─────────────────────┐  signal_id   ┌─────────►│  signal_updates  │  responses
   │      signals        │◄─────────────┤          └──────────────────┘
   │ (central entity)    │              │
   └────┬────────────────┘              │          ┌──────────────────┐
        │ signal_id (nullable)          └─────────►│      tasks       │  volunteer work
        │                          plan_id ───────►│                  │
        ▼                              ▲          └──────────────────┘
   ┌──────────────────┐                │
   │  resource_plans  │────────────────┘          ┌──────────────────┐
   └──────────────────┘                           │   safe_zones     │  shelters / posts
                                                  └──────────────────┘
   ── mesh plumbing ──            ── AI plumbing ──
   ┌──────────────┐               ┌──────────────────┐
   │ seen_packets │               │  mesh_models     │  model registry
   │ sync_log     │               │  inference_cache │  triage cache
   └──────────────┘               │  chat_messages   │  help-assistant log
                                  └──────────────────┘
```

### 6.1 `app_state` — node config (key/value)

| Column | Type | Role |
|---|---|---|
| `key` | TEXT PK | `node_id`, `ui_locale`, `schema_version` |
| `value` | TEXT | the value |

`node_id` is a **stable per-device UUID** seeded on first launch — it replaces today's use of the
display name as an identity (a real bug: two users named "Rahul" collide).

### 6.2 `accounts` — local identity

Replaces the `SharedPreferences` session. Civilians self-register with just a name + role (zero
friction); authorities may carry a `verified` flag set by an out-of-band process.

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | account UUID |
| `node_id` | TEXT | the device this account lives on |
| `name` | TEXT | display name |
| `role` | TEXT | `CIVILIAN \| VOLUNTEER \| AUTHORITY` |
| `contact` | TEXT NULL | optional phone / callsign |
| `verified` | INTEGER | `0/1` — authority credential checked |
| `created_at` | INTEGER | epoch ms |

### 6.3 `signals` — the central domain entity 💡 Borrowed

The structured evolution of today's `Message`. One row = one emergency request.

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | signal UUID, also the gossip dedup key |
| `sender_node_id` | TEXT | origin device |
| `sender_account_id` | TEXT | origin account |
| `sender_name` | TEXT | display name |
| `sender_role` | TEXT | role at time of sending |
| `sender_contact` | TEXT NULL | optional callback contact |
| `category` | TEXT | `MEDICAL \| RESCUE \| RESOURCE \| SAFETY \| INFO` |
| `priority` | TEXT | `CRITICAL \| HIGH \| NORMAL \| LOW` |
| `message` | TEXT | free-text description as entered |
| `summary` | TEXT NULL | AI-generated one-line summary |
| `needs` | TEXT NULL | JSON array, e.g. `["water","medkit"]` |
| `people_count` | INTEGER NULL | people affected |
| `latitude` / `longitude` / `accuracy` | REAL NULL | GPS fix |
| `manual_location` | TEXT NULL | typed location ("near the old bridge") |
| `language` | TEXT | detected language of `message` |
| `original_text` | TEXT NULL | text before translation |
| `original_lang` | TEXT NULL | language of `original_text` |
| `translated_text` | TEXT NULL | English (or common-language) translation |
| `ai_sitrep` | TEXT NULL | JSON — full structured AI triage output |
| `ai_confidence` | REAL NULL | 0–1 confidence of the AI triage |
| `status` | TEXT | `NEW \| ACKNOWLEDGED \| IN_PROGRESS \| RESOLVED \| EXPIRED` |
| `assigned_to_node_id` | TEXT NULL | volunteer/authority handling it |
| `target_role` | TEXT | `ALL \| VOLUNTEER \| AUTHORITY` (display routing) |
| `route_reason` | TEXT NULL | why it was routed where it was |
| `ttl` / `hop_count` | INTEGER | gossip propagation |
| `created_at` / `updated_at` | INTEGER | epoch ms; `updated_at` drives last-write-wins merge |

### 6.4 `signal_updates` — responses (how authorities & volunteers help) 💡 Borrowed

Every action taken *on* a signal is its own gossip-replicated row, so the whole mesh converges on
the same picture of who is doing what.

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | update UUID / dedup key |
| `signal_id` | TEXT | FK → `signals.id` |
| `responder_node_id` / `responder_name` / `responder_role` | TEXT | who acted |
| `action` | TEXT | `ACK \| ASSIGN \| EN_ROUTE \| ON_SCENE \| RESOLVED \| NOTE \| NEED_MORE` |
| `note` | TEXT NULL | free-text detail |
| `latitude` / `longitude` | REAL NULL | responder location at time of update |
| `ttl` / `hop_count` | INTEGER | gossip propagation |
| `created_at` | INTEGER | epoch ms |

Applying updates: the receiving node folds the latest `RESOLVED`/`ASSIGN`/etc. into the parent
`signals` row's `status` and `assigned_to_node_id` (last-write-wins on `created_at`).

### 6.5 `peers` — known mesh nodes

| Column | Type | Role |
|---|---|---|
| `node_id` | TEXT PK | stable node UUID |
| `name` / `role` | TEXT | last-seen identity |
| `has_ai` | INTEGER | `0/1` — can seed the Gemma model |
| `endpoint_id` | TEXT NULL | volatile Nearby endpoint (this session only) |
| `connection_state` | TEXT | `CONNECTED \| SEEN \| LOST` |
| `first_seen` / `last_seen` | INTEGER | epoch ms |

### 6.6 `safe_zones` — authority-designated locations 💡 Borrowed

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | zone UUID / dedup key |
| `name` | TEXT | "Govt School Shelter" |
| `type` | TEXT | `SHELTER \| MEDICAL \| SUPPLY \| EVAC_POINT` |
| `latitude` / `longitude` / `radius_m` | REAL | location + extent |
| `capacity` / `current_occupancy` | INTEGER NULL | crowding info |
| `status` | TEXT | `OPEN \| FULL \| CLOSED` |
| `notes` | TEXT NULL | free text |
| `created_by_node_id` | TEXT | authoring authority |
| `ttl` / `hop_count` | INTEGER | gossip propagation |
| `created_at` / `updated_at` | INTEGER | epoch ms |

### 6.7 `resource_plans` — allocation plans (AI- or authority-generated) 💡 Borrowed

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | plan UUID / dedup key |
| `area_label` | TEXT | human label for the area |
| `center_lat` / `center_lng` | REAL | area centroid |
| `generated_by_node_id` | TEXT | author |
| `generated_by_ai` | INTEGER | `0/1` — produced by Gemma vs hand-made |
| `plan` | TEXT | JSON — per-need allocations (`water`, `food`, `medkits`, `volunteers`) |
| `rationale` | TEXT NULL | AI's reasoning / authority's note |
| `status` | TEXT | `DRAFT \| ACTIVE \| SUPERSEDED` |
| `ttl` / `hop_count` | INTEGER | gossip propagation |
| `created_at` / `updated_at` | INTEGER | epoch ms |

### 6.8 `tasks` — volunteer work items 💡 Borrowed

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | task UUID / dedup key |
| `plan_id` | TEXT NULL | FK → `resource_plans.id` |
| `signal_id` | TEXT NULL | FK → `signals.id` |
| `title` / `description` | TEXT | what to do |
| `category` | TEXT | `MEDICAL \| RESCUE \| RESOURCE \| SAFETY` |
| `assigned_to_node_id` / `assigned_by_node_id` | TEXT | volunteer ← authority |
| `latitude` / `longitude` | REAL NULL | where |
| `status` | TEXT | `ASSIGNED \| ACCEPTED \| EN_ROUTE \| DONE \| CANCELLED` |
| `ttl` / `hop_count` | INTEGER | gossip propagation |
| `created_at` / `updated_at` | INTEGER | epoch ms |

### 6.9 `seen_packets` — gossip dedup

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | packet UUID |
| `packet_type` | TEXT | for diagnostics |
| `origin_node_id` | TEXT | for diagnostics |
| `seen_at` | INTEGER | epoch ms — enables age-based pruning (unlike today's unbounded set) |

### 6.10 `sync_log` — human-readable event feed

| Column | Type | Role |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | row id |
| `level` | TEXT | `INFO \| WARN \| ERROR` |
| `message` | TEXT | "Connected to 3 peers", "Model received from Rahul" |
| `created_at` | INTEGER | epoch ms |

### 6.11 `mesh_models` — model registry 💡 Borrowed

Tracks "what model could a peer give us" vs "what we have", driving the `hasAi` beacon flag and the
transfer state machine (§8).

| Column | Type | Role |
|---|---|---|
| `model_id` | TEXT PK | e.g. `gemma-4-e2b-int4` |
| `runtime` | TEXT | `litert` |
| `display_name` | TEXT | "Gemma 3n E2B (int4)" |
| `size_mb` | INTEGER | advertised size |
| `sha256` | TEXT | expected digest for verification |
| `local_path` | TEXT NULL | filesystem path once `ready` |
| `status` | TEXT | `absent \| downloading \| verifying \| ready \| error` |
| `source` | TEXT NULL | `sideload \| p2p` |
| `error_reason` | TEXT NULL | last failure detail |
| `updated_at` | INTEGER | epoch ms |

### 6.12 `inference_cache` — Gemma triage cache 💡 Borrowed

Skips re-running the model when an identical message was already triaged.

| Column | Type | Role |
|---|---|---|
| `hash` | TEXT PK | SHA-256 of the normalized (trim, collapse whitespace, lowercase) input |
| `task_type` | TEXT | `triage \| translate` |
| `json_response` | TEXT | raw model output |
| `hit_count` | INTEGER | incremented on every hit |
| `created_at` / `last_used_at` | INTEGER | epoch ms |

### 6.13 `chat_messages` — Help Assistant history 💡 Borrowed

| Column | Type | Role |
|---|---|---|
| `id` | TEXT PK | row UUID |
| `account_id` | TEXT | who asked |
| `question` / `answer` | TEXT | the Q&A |
| `lang` | TEXT | language of the exchange |
| `rag_topic` | TEXT NULL | which KB entry grounded the answer |
| `matched_keywords` | TEXT NULL | JSON array — RAG retrieval trace |
| `ms_taken` | INTEGER | inference latency |
| `cached` | INTEGER | `0/1` |
| `created_at` | INTEGER | epoch ms |

### 6.14 Indexes

`signals(status)`, `signals(category)`, `signals(updated_at)`, `signal_updates(signal_id)`,
`tasks(assigned_to_node_id)`, `tasks(status)`, `seen_packets(seen_at)`, `safe_zones(type)`.

The full `CREATE TABLE` DDL is in [Appendix A](#appendix-a--schema-ddl).

---

## 7. Roles, signal lifecycle & how authorities help

### 7.1 Roles ✅ Built

Three roles, each a bundle of display metadata in the `Role` enum
([`Role.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Role.kt)). The unified design renames `USER → CIVILIAN`
for clarity.

| Role | Badge | Accent | What they do |
|---|---|---|---|
| **Civilian** | `CIV` | `#58A6FF` blue | Raise signals, ask the Help Assistant, see safe zones. |
| **Volunteer** | `VOL` | `#3FB950` green | Receive tasks, respond to signals (en-route / on-scene / resolved). |
| **Authority** | `AUTH` | `#F78166` orange | Acknowledge & assign signals, designate safe zones, generate resource plans, create tasks. |

Role drives both the UI (which screens/controls show) and `target_role` routing. **Note:** as in the
current code, `target_role` is *display-side filtering, not security* — all packets reach all nodes
and are stored in plaintext. Real access control / encryption is out of scope for this design.

### 7.2 Signal lifecycle 🔧 Planned

```
 CIVILIAN                        MESH                    VOLUNTEER / AUTHORITY
    │                              │                              │
    │ raise Signal (form)          │                              │
    │  ├─ deterministic triage ────┤  (Layer 1, always — §8.1)     │
    │  ├─ enqueue Gemma enrich ────┤  (Layer 2, async — §8.1)      │
    │  └─ save + gossip ───────────►  signal packet  ──────────────►  appears on dashboard
    │                              │                              │
    │                              │  ◄──── signal_update(ACK) ────┤  authority acknowledges
    │  status: NEW → ACKNOWLEDGED ◄┤                              │
    │                              │  ◄──── signal_update(ASSIGN) ─┤  → volunteer
    │                              │  ◄──── signal_update(EN_ROUTE)┤  volunteer responds
    │  status: → IN_PROGRESS ◄─────┤                              │
    │                              │  ◄──── signal_update(RESOLVED)┤
    │  status: → RESOLVED ◄────────┤                              │
```

Every transition is a gossip-replicated `signal_updates` row; every node folds it into its local
`signals` copy. `EXPIRED` is set locally when `created_at` ages past a retention window.

### 7.3 How authorities provide help

1. **Triage & assign** — authority sees enriched signals on a clustered dashboard, acknowledges, and
   assigns them to volunteer nodes via `signal_update(ASSIGN)`.
2. **Designate safe zones** — authority drops `safe_zones` on the offline map; they gossip to every
   civilian's map.
3. **Plan resources** — authority asks Gemma to summarize an area and propose an allocation
   (`resource_plans`); reviews and marks it `ACTIVE`.
4. **Dispatch tasks** — the plan fans out into `tasks` assigned to nearby volunteer nodes; volunteers
   accept and report progress, all over the mesh.

---

## 8. The AI layer — Gemma on LiteRT 🔧 Planned

### 8.1 Two-layer triage

**Layer 1 — deterministic keyword triage (always runs, no model, synchronous).**
Pure string matching: category inference from keyword sets, `needs` extraction, a headcount regex,
priority from critical/high keyword lists, and a script-range language guess. This runs inline at
signal creation and **guarantees the app is useful with zero AI installed**. (Evolves the current
chat into structured intake — see [`civilian/README.txt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/civilian/README.txt).)

**Layer 2 — Gemma enrichment (async, queued, only if the model is `ready`).**
After creation, a signal is enqueued for the LLM. Gemma produces a structured `ai_sitrep`
(category, priority, summary, needs, confidence). The merge rule: **AI priority is trusted only at
`confidence ≥ 0.7`; otherwise the more severe of {keyword, AI} wins.** An enriched signal arriving
from the mesh (`ai_sitrep` already set) **skips re-inference** — the "smart broadcast" optimization.

### 8.2 What Gemma does (the use cases)

| Use case | Trigger | Flow |
|---|---|---|
| **Signal triage** | every new signal | free text → structured `ai_sitrep` → merged into `signals` |
| **Language conversion** | non-common-language signal | `original_text`/`original_lang` preserved → Gemma translates → `translated_text`; authority replies translated back |
| **Help Assistant** | civilian asks a survival question | keyword **RAG** over a bundled `emergency_kb.json` → retrieved instruction injected as context → Gemma answers → logged to `chat_messages` |
| **Area situation report** | authority opens an area | cluster of signals → Gemma → readable SITREP |
| **Resource allocation** | authority requests a plan | area signals + safe zones → Gemma → `resource_plans.plan` JSON |

### 8.3 Runtime — LiteRT / Google AI Edge

- **Model:** quantized **Gemma 3n E2B** (`gemma-3n-e2b-it-int4`), `.task` format, ~3 GB. **Not
  bundled in the APK** (would break the build at that size) — acquired by in-app download or
  sideload in V1, P2P mesh transfer in V2 (§9).
- **Bridge:** a single `GemmaClient` (Kotlin) is the *only* class that touches LiteRT —
  `init(modelPath)`, `generate(prompt, opts)`, `unload()`. Every other AI feature
  (`SignalClassifier`, `Translator`, `HelpAssistant`, `AreaSummarizer`, `ResourceAllocator`) calls
  `GemmaClient`, per the rule in [`ai/README.txt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/ai/README.txt).
- **Queue discipline:** a single LLM instance, one FIFO job queue, **triage jobs drain before
  assistant jobs**. The watchdog can cancel pending jobs on low battery.
- **Inference cache:** `inference_cache` is checked first; a hit returns instantly with zero
  inference cost.
- **Lifecycle:** the model is **not loaded eagerly** — only after the first mesh heartbeat, only if
  a local model exists, and only if the watchdog isn't in a low-power profile.

---

## 9. Model distribution — P2P transfer + sideload 🔧 Planned 💡 Borrowed · ⏸ Post-V1

> **⏸ Deferred past V1.** V1 delivers the model by **in-app HTTPS download + manual sideload** —
> each device gets its own copy, no mesh involved (see [§2.1](#21-v1-scope-current-build-target-)
> and the V1 testing guide in [§13](#13-v1-testing-guide)). The P2P **mesh** transfer state machine
> below is the V2 target.

A field deployment cannot pre-load every phone. A device that has the model **seeds it to peers**
over Nearby Connections **FILE** payloads.

```
Receiver                                   Seeder
  │ --- model_request --------------------► │   (BYTES, mesh_protocol)
  │                                         │   throttle gate: canServeModel()
  │ ◄-- model_reject (reason) ------------- │   OR reserve a recipient slot
  │ ◄-- model_hash (sha256, sizeMb) ------- │   (BYTES, sidecar)
  │ ◄════════ FILE PAYLOAD ════════════════ │   (Nearby FILE, ~1–2 GB)
  │      progress events → status: downloading
  │      complete → verify SHA-256 → atomic move temp→final → activate (mesh_models: ready)
  │ --- model_complete -------------------► │   (BYTES) release recipient slot
```

- **Sources:** (1) manual sideload (`adb push` / file copy) and (2) P2P transfer from a peer. Both
  land in the `mesh_models` registry.
- **Verification:** SHA-256 of the received file is compared to the advertised `sha256` *before*
  activation. A real digest must be pinned before release.
- **Throttle ("survival etiquette"):** reject `model_request` when battery `< 25%`
  (`low_battery`) or already serving the max concurrent recipients (`too_many_recipients`).
- **Activation** points `GemmaClient` at the new path and sets `mesh_models.status = ready`; it does
  not auto-load the model into RAM (the watchdog decides that).

---

## 10. Power & survival management 🔧 Planned 💡 Borrowed

In a disaster, battery is the scarcest resource. A `BatteryWatchdog` drives power profiles:

| Battery | Behaviour |
|---|---|
| Healthy | Gemma may load; model distribution allowed. |
| `≤ 25%` | Reject incoming model transfer requests. |
| `≤ 20%` | **Unload Gemma from RAM**; cancel queued LLM jobs. Deterministic triage (Layer 1) still runs. |
| `≤ 10%` | Pause *all* model distribution. Mesh messaging continues. |

Hysteresis restore thresholds prevent flapping. The mesh transport and the deterministic layer are
**never** disabled by power state — only the AI enhancements are.

---

## 11. What exists today vs build roadmap

### 11.1 ✅ Built (on `main`, in `DisasterMesh-old/`)

A working **role-tagged flood-chat prototype**: 8 Kotlin files — two activities
([`MainActivity`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt),
[`RoleSetupActivity`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/RoleSetupActivity.kt)), the
[`MeshManager`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt) Nearby Connections wrapper,
[`MessageRepository`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MessageRepository.kt) +
[`db/`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/) Room persistence (single `messages` table),
the [`Message`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt) /
[`Role`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Role.kt) /
[`UserSession`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/UserSession.kt) models, and the
[`MessageAdapter`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/adapter/MessageAdapter.kt). UUID-only flood
propagation, store-and-forward history sync, dark themed UI. The `ai/ map/ civilian/ volunteer/
authority/ models/` packages are **empty scaffolding** (`README.txt` only).

### 11.2 🔧 Build roadmap

| Phase | Deliverable |
|---|---|
| **1 — Domain & routing** | Replace `Message` with `Signal`; `signals` table + migration; `GossipRouter` with TTL/hop-count; `seen_packets`, `peers`, `sync_log`; stable `node_id`; structured intake form + GPS capture. |
| **2 — Response loop** | `signal_updates` table + lifecycle folding; volunteer/authority response UI; `accounts` table. |
| **3 — AI core** | LiteRT `GemmaClient`; deterministic Layer-1 triage; Gemma Layer-2 enrichment; `inference_cache`; LLM job queue. |
| **4 — Assist & translate** | Help Assistant + keyword RAG over bundled `emergency_kb.json`; language conversion; `chat_messages`. |
| **5 — Model distribution** | `mesh_models` registry; P2P FILE transfer state machine; SHA-256 verifier; `BatteryWatchdog` + transfer throttle. |
| **6 — Coordination & maps** | OSMDroid offline maps; `safe_zones`; signal clustering; `resource_plans` + `tasks`; full civilian/volunteer/authority Fragment screens. |

---

## 12. Module map (proposed package structure)

| Package | Responsibility | Status |
|---|---|---|
| `core/` | `UserSession`, identity (`node_id`, `createId`), `app_state` access | ✅ partial |
| `model/` | Pure data classes: `Signal`, `SignalUpdate`, `SafeZone`, `ResourcePlan`, `Task`, `Role`, enums | 🔧 (`models/` README exists) |
| `db/` | Room: `AppDatabase`, DAOs, entities, migrations, last-write-wins merge | ✅ partial |
| `mesh/` | `MeshManager` (Nearby Connections), `GossipRouter` (TTL/hop-count), `MeshPacket` types | ✅ / 🔧 |
| `meshmodels/` | `mesh_models` registry, model-transfer state machine, SHA-256 verifier, transfer throttle | 🔧 |
| `ai/` | `GemmaClient` (LiteRT), `SignalClassifier`, `Translator`, `HelpAssistant`, `AreaSummarizer`, `ResourceAllocator`, RAG, LLM queue, inference cache | 🔧 (README exists) |
| `power/` | `BatteryWatchdog`, power profiles | 🔧 |
| `civilian/` | Civilian screens: signal intake form, my-signals, help assistant | 🔧 (README exists) |
| `volunteer/` | Volunteer screens: task list, respond/complete | 🔧 (README exists) |
| `authority/` | Authority screens: area dashboard, signal detail, resource-plan view | 🔧 (README exists) |
| `map/` | OSMDroid offline map, signal pins, safe-zone overlays, clustering | 🔧 (README exists) |

**Architectural rule (from the existing READMEs, kept):** only `ai/GemmaClient` touches LiteRT; only
`mesh/` touches Nearby Connections. Role packages never call them directly — they go through the
domain `*Service` layer.

---

## 13. V1 testing guide

V1 = the existing mesh chat **+** an on-device Gemma Help Assistant. Test the two independently.

### 13.1 Prerequisites

- Android Studio + an Android device with **API ≥ 24** (V1 raises `minSdk` 23 → 24 for LiteRT).
- `adb` on PATH.
- A **MediaPipe-compatible Gemma `.task` file** (int4-quantized). Target is **Gemma 3n E2B**; a
  smaller **Gemma 3 1B** `.task` is fine for a first run and faster on low-end phones.
- For the **in-app download** path: that `.task` file **re-hosted at a direct HTTPS URL you
  control** (Gemma is licence-gated on Hugging Face / Kaggle and cannot be hot-linked — see
  §13.3). For the **sideload** path you don't need a URL.

### 13.2 Build & install

```bash
cd DisasterMesh-old
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 13.3 Get the model onto the device — pick one

`GemmaClient` looks for the model at exactly:
`/sdcard/Android/data/com.disastermesh/files/models/gemma.task`

**Path A — in-app download (the CODM/PUBG-style flow):**
1. Accept the Gemma licence on Hugging Face / Kaggle and download the `.task` once.
2. Re-host it at a direct HTTPS link (Firebase Storage, S3/GCS, a GitHub Release asset, your own
   server …).
3. Set `MODEL_URL` in [`ai/ModelDownloader.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/ai/ModelDownloader.kt) to that link; rebuild.
4. In the app: **🤖 → Download model** → watch the progress bar. The download is resumable and
   keeps running if you leave the screen.

**Path B — manual sideload (no URL needed):** writable by `adb` with no runtime permission:

```bash
adb shell mkdir -p /sdcard/Android/data/com.disastermesh/files/models
adb push your-gemma-model.task /sdcard/Android/data/com.disastermesh/files/models/gemma.task
```

### 13.4 Test the AI (per device)

1. Launch the app, pick a role + name.
2. Tap **🤖** in the header → the **Assistant** screen.
3. The status line / panel shows the model lifecycle:
   - `Model needed` → the download panel is shown (use Path A or B in §13.3).
   - `Loading model…` → LiteRT is initialising (first load takes a while).
   - `Ready` → ask a question, e.g. *"How do I treat a deep cut with no first-aid kit?"*
4. A grounded answer appears within seconds–tens of seconds (device-dependent). Inference runs on a
   background thread — the UI stays responsive.

### 13.5 Regression-test the mesh (unchanged behaviour)

On **two** devices with the app installed (model optional):

1. Set up both — any roles.
2. Grant the Bluetooth / Wi-Fi / location permissions when prompted.
3. Header should show `● 1 peer connected` once they discover each other.
4. Send a message on device A → it appears on device B; reply → appears on A.
5. Kill and reopen the app → history reloads from SQLite.

This must behave **exactly as before V1** — the mesh code was not touched.

### 13.6 Negative / no-AI test

Run the app on a device **without** the model file. The Assistant screen must show the **download
panel** cleanly (or, if `MODEL_URL` is unset, a clear "no URL configured" message), and **chat must
work normally** — proving AI is a pure add-on and its absence degrades nothing.

### 13.7 Download-flow test

1. With a valid `MODEL_URL`: **🤖 → Download model** → progress bar advances, shows `MB / MB (%)`.
2. Tap **Cancel** mid-download → leave and reopen the screen → tap **Retry** → it **resumes** (does
   not restart from 0).
3. On completion the panel disappears and the model loads to `Ready`.

### 13.8 Quick checklist

| Check | Pass condition |
|---|---|
| App builds | `assembleDebug` succeeds |
| Mesh unaffected | 2-device send/receive + history reload still work |
| Model absent → graceful | Download panel shows; chat unaffected |
| In-app download | Progress bar advances; Cancel + Retry resumes |
| Model present → loads | Status reaches `Ready` |
| Inference works | A sensible answer returns; UI not frozen |

---

## Appendix A — Schema DDL

```sql
CREATE TABLE app_state (
  key   TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE accounts (
  id         TEXT PRIMARY KEY,
  node_id    TEXT NOT NULL,
  name       TEXT NOT NULL,
  role       TEXT NOT NULL,             -- CIVILIAN | VOLUNTEER | AUTHORITY
  contact    TEXT,
  verified   INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE peers (
  node_id          TEXT PRIMARY KEY,
  name             TEXT,
  role             TEXT,
  has_ai           INTEGER NOT NULL DEFAULT 0,
  endpoint_id      TEXT,
  connection_state TEXT,                -- CONNECTED | SEEN | LOST
  first_seen       INTEGER,
  last_seen        INTEGER
);

CREATE TABLE signals (
  id                  TEXT PRIMARY KEY,
  sender_node_id      TEXT NOT NULL,
  sender_account_id   TEXT,
  sender_name         TEXT,
  sender_role         TEXT,
  sender_contact      TEXT,
  category            TEXT,             -- MEDICAL | RESCUE | RESOURCE | SAFETY | INFO
  priority            TEXT,             -- CRITICAL | HIGH | NORMAL | LOW
  message             TEXT NOT NULL,
  summary             TEXT,
  needs               TEXT,             -- JSON array
  people_count        INTEGER,
  latitude            REAL,
  longitude           REAL,
  accuracy            REAL,
  manual_location     TEXT,
  language            TEXT,
  original_text       TEXT,
  original_lang       TEXT,
  translated_text     TEXT,
  ai_sitrep           TEXT,             -- JSON
  ai_confidence       REAL,
  status              TEXT NOT NULL DEFAULT 'NEW',
  assigned_to_node_id TEXT,
  target_role         TEXT NOT NULL DEFAULT 'ALL',
  route_reason        TEXT,
  ttl                 INTEGER NOT NULL DEFAULT 8,
  hop_count           INTEGER NOT NULL DEFAULT 0,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL
);
CREATE INDEX idx_signals_status   ON signals(status);
CREATE INDEX idx_signals_category ON signals(category);
CREATE INDEX idx_signals_updated  ON signals(updated_at);

CREATE TABLE signal_updates (
  id                TEXT PRIMARY KEY,
  signal_id         TEXT NOT NULL,
  responder_node_id TEXT NOT NULL,
  responder_name    TEXT,
  responder_role    TEXT,
  action            TEXT NOT NULL,      -- ACK | ASSIGN | EN_ROUTE | ON_SCENE | RESOLVED | NOTE | NEED_MORE
  note              TEXT,
  latitude          REAL,
  longitude         REAL,
  ttl               INTEGER NOT NULL DEFAULT 8,
  hop_count         INTEGER NOT NULL DEFAULT 0,
  created_at        INTEGER NOT NULL
);
CREATE INDEX idx_signal_updates_signal ON signal_updates(signal_id);

CREATE TABLE safe_zones (
  id                 TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  type               TEXT NOT NULL,     -- SHELTER | MEDICAL | SUPPLY | EVAC_POINT
  latitude           REAL NOT NULL,
  longitude          REAL NOT NULL,
  radius_m           REAL,
  capacity           INTEGER,
  current_occupancy  INTEGER,
  status             TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN | FULL | CLOSED
  notes              TEXT,
  created_by_node_id TEXT NOT NULL,
  ttl                INTEGER NOT NULL DEFAULT 8,
  hop_count          INTEGER NOT NULL DEFAULT 0,
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);
CREATE INDEX idx_safe_zones_type ON safe_zones(type);

CREATE TABLE resource_plans (
  id                   TEXT PRIMARY KEY,
  area_label           TEXT,
  center_lat           REAL,
  center_lng           REAL,
  generated_by_node_id TEXT NOT NULL,
  generated_by_ai      INTEGER NOT NULL DEFAULT 0,
  plan                 TEXT NOT NULL,   -- JSON
  rationale            TEXT,
  status               TEXT NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ACTIVE | SUPERSEDED
  ttl                  INTEGER NOT NULL DEFAULT 8,
  hop_count            INTEGER NOT NULL DEFAULT 0,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL
);

CREATE TABLE tasks (
  id                  TEXT PRIMARY KEY,
  plan_id             TEXT,
  signal_id           TEXT,
  title               TEXT NOT NULL,
  description         TEXT,
  category            TEXT,
  assigned_to_node_id TEXT NOT NULL,
  assigned_by_node_id TEXT NOT NULL,
  latitude            REAL,
  longitude           REAL,
  status              TEXT NOT NULL DEFAULT 'ASSIGNED',  -- ASSIGNED | ACCEPTED | EN_ROUTE | DONE | CANCELLED
  ttl                 INTEGER NOT NULL DEFAULT 8,
  hop_count           INTEGER NOT NULL DEFAULT 0,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL
);
CREATE INDEX idx_tasks_assignee ON tasks(assigned_to_node_id);
CREATE INDEX idx_tasks_status   ON tasks(status);

CREATE TABLE seen_packets (
  id             TEXT PRIMARY KEY,
  packet_type    TEXT,
  origin_node_id TEXT,
  seen_at        INTEGER NOT NULL
);
CREATE INDEX idx_seen_packets_seen_at ON seen_packets(seen_at);

CREATE TABLE sync_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  level      TEXT NOT NULL DEFAULT 'INFO',
  message    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE mesh_models (
  model_id     TEXT PRIMARY KEY,
  runtime      TEXT NOT NULL DEFAULT 'litert',
  display_name TEXT,
  size_mb      INTEGER,
  sha256       TEXT,
  local_path   TEXT,
  status       TEXT NOT NULL DEFAULT 'absent',  -- absent | downloading | verifying | ready | error
  source       TEXT,                            -- sideload | p2p
  error_reason TEXT,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE inference_cache (
  hash          TEXT PRIMARY KEY,                -- SHA-256 of normalized input
  task_type     TEXT NOT NULL,                   -- triage | translate
  json_response TEXT NOT NULL,
  hit_count     INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER NOT NULL
);

CREATE TABLE chat_messages (
  id               TEXT PRIMARY KEY,
  account_id       TEXT,
  question         TEXT NOT NULL,
  answer           TEXT,
  lang             TEXT,
  rag_topic        TEXT,
  matched_keywords TEXT,                         -- JSON array
  ms_taken         INTEGER,
  cached           INTEGER NOT NULL DEFAULT 0,
  created_at       INTEGER NOT NULL
);
```

---

*This document consolidates `SYSTEM_ARCHITECTURE.md` (ground truth — native Android code on `main`)
and `SYSTEM_KNOWLEDGE_SPEC.md` (the RN rewrite, re-targeted to native Android). Where a `🔧 Planned`
section conflicts with current code, the code is the present and this document is the target.*
