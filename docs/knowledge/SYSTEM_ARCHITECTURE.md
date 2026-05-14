# System Architecture — `DisasterMesh-old` (native Android prototype)

> **Scope of this document:** the code that *actually exists on the `main` branch today*.
> That is the native Android/Kotlin app under [`DisasterMesh-old/`](../../DisasterMesh-old/) —
> nothing else. It is described as built, not as planned.
>
> **What this is NOT:** the React Native + Expo rewrite (`offline-disaster-app/`, a.k.a.
> "DisasterMesh-New") documented in [`SYSTEM_KNOWLEDGE_SPEC.md`](./SYSTEM_KNOWLEDGE_SPEC.md).
> That codebase was moved off `main` to the `disaster-mesh-new` branch (commit `4d63513`) and is
> **not present here**. See [§10 Relationship to the RN rewrite](#10-relationship-to-the-rn-rewrite)
> for how the two compare.

---

## 1. What the app is

DisasterMesh is an **offline, infrastructure-free emergency chat app for Android**. When cell towers
and Wi-Fi are down (earthquake, flood, cyclone), phones running this app form a **peer-to-peer mesh**
over Bluetooth / Wi-Fi Direct and relay short text messages hop-by-hop. There is **no server, no
internet, no account** — every phone is both a client and a relay.

The current build is a **working prototype**: a role-tagged group chat with store-and-forward
delivery and flood-based propagation. The richer "emergency signal coordination" product (structured
signals, AI triage, offline maps, resource planning) exists only as **empty scaffolding** — see
[§9 Planned but not built](#9-planned-but-not-built).

### 1.1 Core design principles

| Principle | How it shows up in code |
|---|---|
| **No infrastructure** | No HTTP, no sockets, no backend. Transport is Google Nearby Connections only. |
| **Every node is a relay** | On receiving a *new* message, a node re-broadcasts it to all its other peers ([`MeshManager.kt:157`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt#L157)). |
| **Store-and-forward** | Every message is persisted to SQLite; a freshly connected peer is sent the full history ([`MeshManager.kt:175-183`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt#L175-L183)). |
| **Works with one tap** | First launch → pick a role + name → you are on the mesh. No login, no config. |
| **Offline-first persistence** | Room/SQLite DB survives app restarts; messages reload into memory on startup. |

---

## 2. Tech stack

| Layer | Choice | Detail / Source |
|---|---|---|
| Language | **Kotlin** `1.9.22`, JVM target 1.8 | [`build.gradle`](../../DisasterMesh-old/build.gradle), [`app/build.gradle:31-33`](../../DisasterMesh-old/app/build.gradle#L31-L33) |
| Build system | **Gradle 8.6**, Android Gradle Plugin `8.2.2`, `kotlin-kapt` | [`gradle-wrapper.properties:3`](../../DisasterMesh-old/gradle/wrapper/gradle-wrapper.properties#L3), [`app/build.gradle:1-5`](../../DisasterMesh-old/app/build.gradle#L1-L5) |
| Android target | `minSdk 23` (Android 6.0), `compileSdk` / `targetSdk 34` (Android 14) | [`app/build.gradle:9-16`](../../DisasterMesh-old/app/build.gradle#L9-L16) |
| App identity | `applicationId` / `namespace` = `com.disastermesh`, `versionCode 1`, `versionName "1.0"` | [`app/build.gradle:8-16`](../../DisasterMesh-old/app/build.gradle#L8-L16) |
| UI toolkit | **Classic Android Views** — XML layouts + `findViewById`. No Jetpack Compose, no ViewBinding. | `res/layout/*.xml`, `AppCompatActivity` |
| UI libraries | `androidx.appcompat 1.6.1`, `material 1.11.0`, `recyclerview 1.3.2`, `core-ktx 1.12.0` | [`app/build.gradle:37-40`](../../DisasterMesh-old/app/build.gradle#L37-L40) |
| P2P transport | **Google Nearby Connections** (`play-services-nearby 19.1.0`) | [`app/build.gradle:41`](../../DisasterMesh-old/app/build.gradle#L41) |
| Persistence | **Room 2.6.1** over SQLite (`runtime`, `ktx`, compiler via `kapt`) | [`app/build.gradle:44-47`](../../DisasterMesh-old/app/build.gradle#L44-L47) |
| Wire serialization | **`org.json`** (Android built-in) — messages are JSON strings | [`Message.kt:3`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt#L3) |
| Lightweight state | **`SharedPreferences`** for the user session | [`UserSession.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/UserSession.kt) |

**Notable non-choices:** no DI framework, no coroutines/RxJava (Room runs with
`allowMainThreadQueries()`), no navigation component (two plain activities), no networking library,
no encryption library.

---

## 3. Project layout

```
DisasterMesh-old/
├── build.gradle                    # root: plugin versions only
├── settings.gradle                 # rootProject "DisasterMesh", includes :app
├── gradle.properties               # AndroidX on, official Kotlin style
├── gradle/wrapper/                  # Gradle 8.6 wrapper
└── app/
    ├── build.gradle                # module deps + Android config
    └── src/main/
        ├── AndroidManifest.xml     # permissions + 2 activities
        ├── java/com/disastermesh/
        │   ├── MainActivity.kt          # main chat screen + permissions
        │   ├── RoleSetupActivity.kt     # first-run onboarding
        │   ├── MeshManager.kt           # Nearby Connections wrapper (the mesh)
        │   ├── MessageRepository.kt     # in-memory cache + dedup over Room
        │   ├── Message.kt               # wire model + JSON (de)serialization
        │   ├── Role.kt                  # USER / VOLUNTEER / AUTHORITY enum
        │   ├── UserSession.kt           # SharedPreferences-backed identity
        │   ├── adapter/MessageAdapter.kt # RecyclerView adapter for chat bubbles
        │   ├── db/                       # Room: AppDatabase, MessageDao, MessageEntity
        │   ├── ai/        README.txt only  ─┐
        │   ├── civilian/  README.txt only   │ empty scaffolding —
        │   ├── volunteer/ README.txt only   │ see §9
        │   ├── authority/ README.txt only   │
        │   ├── map/       README.txt only   │
        │   └── models/    README.txt only  ─┘
        └── res/
            ├── layout/    activity_main, activity_role_setup, item_message
            ├── drawable/  bubble/badge/input/spinner shapes
            └── values/    colors, strings, themes (dark "GitHub" palette)
```

Real Kotlin lives in **8 files**; the six folders containing only `README.txt` are intended module
boundaries with nothing implemented yet.

---

## 4. Runtime architecture

The app is a **single process, two activities, no background service**. Everything is driven from
the foreground.

```
┌──────────────────────────────────────────────────────────────────┐
│  Android process  com.disastermesh                                │
│                                                                    │
│   RoleSetupActivity ──(first run, save name+role)──► UserSession   │
│         │                                            (SharedPrefs) │
│         ▼                                                          │
│   MainActivity ───────────────────────────────────────────────┐   │
│     │  • binds views, applies role theme                       │   │
│     │  • requests runtime permissions                          │   │
│     │  • owns MessageAdapter (RecyclerView)                     │   │
│     │                                                          │   │
│     ├──► MeshManager ──────────────────────────────┐           │   │
│     │      • Nearby Connections client             │           │   │
│     │      • advertise + discover (P2P_CLUSTER)    │           │   │
│     │      • connection + payload callbacks        │           │   │
│     │      • flood broadcast / re-broadcast        │           │   │
│     │             │                                │           │   │
│     │             ▼                                │           │   │
│     └──► MessageRepository ◄─────────────────────────┘          │   │
│              • in-memory seenIds (dedup)                        │   │
│              • in-memory _messages list                         │   │
│              • Room AppDatabase ──► SQLite "disaster_mesh.db"   │   │
│                                                                  │   │
└──────────────────────────────────────────────────────────────────┘
        │  Nearby Connections (Bluetooth / BLE / Wi-Fi Direct / hotspot)
        ▼
   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
   │  Peer phone │◄─►│  Peer phone │◄─►│  Peer phone │   … mesh cluster
   └─────────────┘   └─────────────┘   └─────────────┘
```

### 4.1 Component responsibilities

| Component | File | Responsibility |
|---|---|---|
| `RoleSetupActivity` | [RoleSetupActivity.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/RoleSetupActivity.kt) | First-run onboarding: pick one of three role cards, enter a display name (≥2 chars), persist via `UserSession`, launch `MainActivity`. |
| `MainActivity` | [MainActivity.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt) | The whole UI. Redirects to setup if not configured, binds views, applies a **role-driven theme**, wires `MeshManager` callbacks to the UI, requests permissions, handles send. Tears the mesh down in `onDestroy`. |
| `MeshManager` | [MeshManager.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt) | The mesh engine. Wraps the Nearby Connections client: advertising, discovery, connection lifecycle, payload send/receive, flood re-broadcast, and history sync to new peers. |
| `MessageRepository` | [MessageRepository.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MessageRepository.kt) | The dedup + persistence gate. Holds an in-memory `seenIds` set and `_messages` list, both preloaded from SQLite on construction. `add()` returns `true` only for genuinely new messages. |
| `AppDatabase` / `MessageDao` / `MessageEntity` | [db/](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/) | Room persistence. Single-table DB `disaster_mesh.db`, version 1, `allowMainThreadQueries()`, singleton instance. |
| `Message` | [Message.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt) | The domain + wire model. `data class` with `toJson()` / `fromJson()` and a role-visibility rule. |
| `Role` | [Role.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Role.kt) | `enum` of `USER` / `VOLUNTEER` / `AUTHORITY`, each carrying display metadata (name, emoji, colors, badge label). |
| `UserSession` | [UserSession.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/UserSession.kt) | `SharedPreferences` wrapper for the local identity (`name`, `role`) and the `isSetup` gate. |
| `MessageAdapter` | [adapter/MessageAdapter.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/adapter/MessageAdapter.kt) | `RecyclerView.Adapter` rendering chat bubbles: local vs peer styling, role colors, role badge, target label. |

There is **no ViewModel, no repository injection, no observer framework** — `MainActivity`
constructs `MeshManager` directly and passes it two lambdas (`onMessageReceived`, `onPeersChanged`)
that mutate the UI on `runOnUiThread`.

---

## 5. Data model & schema

### 5.1 `Message` — the one domain entity

Defined in [`Message.kt:6-14`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt#L6-L14). It is simultaneously the
**in-memory model**, the **wire format** (via `toJson`/`fromJson`), and — through `MessageEntity` —
the **DB row**.

| Field | Type | Meaning |
|---|---|---|
| `id` | `String` | `UUID.randomUUID()`. **Primary key and the dedup key** — the only thing that stops flood loops. |
| `senderId` | `String` | Currently set to the device's **display name** (see [§8 limitations](#8-known-limitations--gotchas)). |
| `senderName` | `String` | Display name shown in the UI. |
| `senderRole` | `String` | `"USER"` / `"VOLUNTEER"` / `"AUTHORITY"` — the enum `name`. Defaults to `USER`. |
| `text` | `String` | The message body. |
| `targetRole` | `String` | `"ALL"` / `"VOLUNTEER"` / `"AUTHORITY"` — intended audience. Defaults to `"ALL"`. |
| `timestamp` | `Long` | `System.currentTimeMillis()` at creation. |

**JSON wire format** (one BYTES payload = one UTF-8 JSON object,
[`Message.kt:15-37`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt#L15-L37)):

```json
{ "id": "...", "senderId": "...", "senderName": "...", "senderRole": "USER",
  "text": "...", "targetRole": "ALL", "timestamp": 1715600000000 }
```

`fromJson` uses `optString` with defaults for `senderRole` and `targetRole`, so it tolerates older
peers that omit those fields.

### 5.2 SQLite schema — `disaster_mesh.db`

Room database, **version 1**, `exportSchema = false`, opened as a process-wide singleton with
`allowMainThreadQueries()` ([`AppDatabase.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/AppDatabase.kt)).

**Table `messages`** ([`MessageEntity.kt:8-17`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/MessageEntity.kt#L8-L17)) — a 1:1 mirror of `Message`:

| Column | Type | Constraint |
|---|---|---|
| `id` | `TEXT` | **PRIMARY KEY** |
| `senderId` | `TEXT` | |
| `senderName` | `TEXT` | |
| `senderRole` | `TEXT` | |
| `text` | `TEXT` | |
| `targetRole` | `TEXT` | |
| `timestamp` | `INTEGER` | |

**DAO operations** ([`MessageDao.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/MessageDao.kt)):

| Method | SQL | Used for |
|---|---|---|
| `insert` | `INSERT … OnConflictStrategy.IGNORE` | Persist a message; the **PK conflict → IGNORE** is the DB-level dedup. |
| `getAll` | `SELECT * FROM messages ORDER BY timestamp DESC` | Preload on startup; supply history to new peers. |
| `getAllIds` | `SELECT id FROM messages` | (Defined, currently unused.) |
| `count` | `SELECT COUNT(*) FROM messages` | (Defined, currently unused.) |
| `clear` | `DELETE FROM messages` | `MessageRepository.clear()` (currently unused by UI). |

There is **no migration path** — schema version is fixed at 1 and `exportSchema` is off.

### 5.3 `SharedPreferences` — `"session"`

The local identity, not in the DB ([`UserSession.kt`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/UserSession.kt)):

| Key | Value | Notes |
|---|---|---|
| `name` | display name string | trimmed on save |
| `role` | `Role.name` string | read back via `Role.fromName`, which **defaults to `USER`** on anything unknown |

`isSetup` = `prefs.contains("role") && name.isNotBlank()` — this single boolean decides whether
`MainActivity` redirects to onboarding.

---

## 6. How it works — end-to-end flows

### 6.1 First launch / onboarding

```
App start → MainActivity.onCreate
   └─ UserSession.isSetup == false
        └─ startActivity(RoleSetupActivity); finish()
             └─ user taps a role card  → selectRole() recolors cards + Join button
             └─ user types name (≥2 chars)
             └─ Join → UserSession.save(name, role) → MainActivity → finish()
```

On every subsequent launch `isSetup` is `true`, so `MainActivity` proceeds directly.

### 6.2 Joining the mesh

```
MainActivity.onCreate
   ├─ bindViews()            : pull views, set name + role badge
   ├─ applyRoleTheme()       : show/hide USER-only rows, recolor Send button, set input hint
   ├─ setupTargetSpinner()   : "Everyone / Volunteers only / Authorities only"
   ├─ setupRecyclerView()    : MessageAdapter(localDeviceId = session.name, myRole = session.role)
   ├─ setupMeshManager()     : construct MeshManager with 2 UI callbacks
   ├─ setupSendButton(), setupQuickChips()
   └─ checkAndRequestPermissions()
        ├─ all granted   → meshManager.start()
        └─ else          → ActivityCompat.requestPermissions(...)
                              └─ onRequestPermissionsResult → all granted → meshManager.start()

meshManager.start()
   ├─ startAdvertising()  : Nearby.startAdvertising(deviceName, SERVICE_ID, …, P2P_CLUSTER)
   └─ startDiscovery()    : Nearby.startDiscovery(SERVICE_ID, …, P2P_CLUSTER)
```

`MessageRepository` is constructed *inside* `MeshManager`, and its `init {}` block immediately
reloads all stored messages into `seenIds` + `_messages` — so a returning user's history is
in memory before the first peer connects.

### 6.3 Peer connection lifecycle

Both devices advertise **and** discover simultaneously (`P2P_CLUSTER` is a many-to-many strategy):

```
Device A discovers Device B
  onEndpointFound(B)            → client.requestConnection(A.name, B, …)
  onConnectionInitiated(B)      → client.acceptConnection(B, payloadCallback)   ← auto-accept, no auth
  onConnectionResult(B, OK)     → connectedEndpoints[B] = …
                                  notifyPeers()           → MainActivity updates "● N peers connected"
                                  syncHistoryTo(B)        → send every stored Message to B as BYTES
  onDisconnected(B)             → connectedEndpoints.remove(B); notifyPeers()
```

Note: both sides typically call `requestConnection` on each other; one request fails harmlessly and
the other succeeds — this is expected and explicitly logged, not an error
([`MeshManager.kt:109-113`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt#L109-L113)).

### 6.4 Sending a message

```
user taps Send (or IME "send")
  → MainActivity.sendMessage()
       text = trimmed input;  if empty → return
       target = (role == USER) ? currentTarget : "ALL"     ← only civilians can target
  → meshManager.sendMessage(text, senderRole, target)
       build Message(id = new UUID, senderId = senderName, …)
       repository.add(message)               → true (new)
          ├─ onMessageReceived(message)      → MainActivity renders it locally (if visibleTo me)
          └─ broadcast(message, exclude=null) → Payload.fromBytes(json) to every connected endpoint
```

### 6.5 Receiving & relaying (the flood algorithm)

```
payloadCallback.onPayloadReceived(fromEndpoint, payload)
  bytes → JSON string → Message.fromJson(json)
  isNew = repository.add(message)
     ├─ NEW   → onMessageReceived(message)              → UI renders (if visibleTo me)
     │         broadcast(message, exclude = fromEndpoint) → relay to all OTHER peers
     └─ DUP   → drop silently  (seenIds already had message.id)
  parse failure → caught + logged, payload ignored
```

This is **controlled flooding**: every node forwards each message exactly once (the first time it
sees it), to everyone except the peer it came from. The **message `id` (UUID) is the only loop
breaker** — there is no TTL, no hop count, no time-to-live. A message propagates across the entire
connected component of the mesh and then stops because every node has it in `seenIds`.

`onPayloadTransferUpdate` is a no-op — payloads are small text BYTES, so progress tracking is
unnecessary.

### 6.6 Store-and-forward for late joiners

When peer B connects, A immediately runs `syncHistoryTo(B)` and sends **its entire message
history** ([`MeshManager.kt:175-183`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt#L175-L183)). B's `repository.add()` dedup silently drops anything B
already had, so B ends up with the union of both histories. Combined with flooding, this gives
**eventual consistency** across the mesh: a phone that was off during an event still receives the
backlog the moment it reconnects to anyone who has it.

---

## 7. Roles, targeting & UI

### 7.1 The `Role` enum

Three roles, each a bundle of display metadata
([`Role.kt:5-36`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Role.kt#L5-L36)):

| Enum | Display name | Badge | Accent color | Subtitle |
|---|---|---|---|---|
| `USER` | Civilian | `CIV` | `#58A6FF` (blue) | "I need help or want to report" |
| `VOLUNTEER` | Volunteer | `VOL` | `#3FB950` (green) | "I'm here to help people" |
| `AUTHORITY` | Authority | `AUTH` | `#F78166` (orange) | "Official emergency responder" |

The role drives the entire visual identity: badge color, Send-button tint, input hint text, and
which controls are visible.

### 7.2 Role-conditional UI (`applyRoleTheme`)

[`MainActivity.kt:104-126`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt#L104-L126):

- **Civilians only** see the quick-action chips (`🚨 SOS`, `🏥 Medical`, `📦 Supply`) and the
  "Send to:" target spinner.
- Volunteers and Authorities have those rows hidden — they always broadcast to `ALL`.
- Quick chips prefill the input box *and* preselect a sensible target (SOS → Everyone,
  Medical → Volunteers only) ([`MainActivity.kt:191-209`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt#L191-L209)).

### 7.3 Targeting is display-side filtering, not access control

`Message.isVisibleTo(myRole)` ([`Message.kt:40-44`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt#L40-L44)):

| `targetRole` | Visible to |
|---|---|
| `"ALL"` | everyone |
| `"VOLUNTEER"` | `VOLUNTEER` and `AUTHORITY` |
| `"AUTHORITY"` | `AUTHORITY` only |

**Important:** this filter is applied **only when rendering** — in `MainActivity`'s
`onMessageReceived` callback ([`MainActivity.kt:160-167`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt#L160-L167)). A targeted message is still:
- broadcast to **every** peer regardless of role,
- relayed by every node, and
- **stored in every node's SQLite DB** in plaintext.

So targeting is a UX courtesy ("don't show civilians the authority chatter"), **not security**. Any
peer can read any message off the wire or out of the DB.

### 7.4 Chat rendering (`MessageAdapter`)

[adapter/MessageAdapter.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/adapter/MessageAdapter.kt) renders each row:

- **Local vs peer** is decided by `msg.senderId == localDeviceId` (where `localDeviceId` is the
  user's name). Local messages get the blue `bubble_local` drawable, white text, and are
  right-aligned via a `LAYOUT_DIRECTION_RTL` trick; peer messages get the gray `bubble_peer`
  drawable, left-aligned.
- Sender name is shown as `"You"` for local messages; the role badge is hidden for local messages.
- A `→ Volunteers` / `→ Authorities` target label appears on peer messages that weren't sent to all.
- New messages are inserted at index 0; the `RecyclerView` scrolls to top.

The visual theme is a dark "GitHub" palette (`#0D1117` background, `#161B22` surfaces) defined in
[`values/colors.xml`](../../DisasterMesh-old/app/src/main/res/values/colors.xml) and [`values/themes.xml`](../../DisasterMesh-old/app/src/main/res/values/themes.xml).

---

## 8. Permissions & platform constraints

Declared in [`AndroidManifest.xml`](../../DisasterMesh-old/app/src/main/AndroidManifest.xml) and requested at runtime in
[`MainActivity.kt:46-64`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt#L46-L64) — the set is **SDK-version-conditional** because Nearby Connections'
permission requirements changed across Android releases:

| Permission | When | Why |
|---|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | API ≤ 30 (`maxSdkVersion="30"`) | Legacy Bluetooth on Android 11 and below |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | API ≥ 31 | Android 12+ granular Bluetooth |
| `NEARBY_WIFI_DEVICES` | API ≥ 33 | Android 13+ Wi-Fi peer discovery |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | always | Wi-Fi Direct transport |
| `ACCESS_FINE_LOCATION` | always | Required by Nearby Connections discovery on most versions |

The mesh **does not start until every requested permission is granted**
([`MainActivity.kt:221-236`](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt#L221-L236)); a denial shows a Toast and leaves the app idle.

**Nearby Connections specifics:** `Strategy.P2P_CLUSTER` (M-to-N mesh, as opposed to star/point-to-point),
`SERVICE_ID = "com.disastermesh.mesh"`. The OS picks the physical medium (Bluetooth, BLE, Wi-Fi
Direct, Wi-Fi hotspot) automatically; effective range is roughly Bluetooth/Wi-Fi-Direct distance
(tens of metres), extended arbitrarily far by **multi-hop relaying**.

**Platform:** Android only. Two activities, both in the manifest; `MainActivity` is the launcher.
`allowBackup="true"`, `supportsRtl="true"`.

---

## 9. Known limitations & gotchas

These are real characteristics of the current code — a companion or follow-on project should be
aware of them.

| Area | Limitation |
|---|---|
| **Identity** | `senderId` is set to the user's **display name**, not a stable device ID. Two users with the same name collide: the adapter's "is this mine?" check (`senderId == localDeviceId`) and dedup attribution break. |
| **Dedup memory** | `seenIds` is an **unbounded in-memory `Set`** preloaded from the full DB. It grows for the life of the install; there is no eviction, TTL, or cap. |
| **No TTL / hop limit** | Flood termination relies *entirely* on UUID dedup. There is no hop count or expiry — fine for a small cluster, but every message lives forever in every node's DB. |
| **History sync cost** | `syncHistoryTo` sends the **entire** message history to every newly connected peer, one payload per message. O(messages) traffic per connection event. |
| **No security** | Wire payloads are **plaintext JSON**. No encryption, no signing, no peer authentication (`acceptConnection` is unconditional). Role and targeting are self-declared and trivially spoofable. |
| **Main-thread DB** | Room runs with `allowMainThreadQueries()`. Acceptable for tiny text rows; would jank under load. |
| **No background service** | The mesh lives in `MainActivity`. `onDestroy` calls `meshManager.stop()` — leaving/closing the app drops the device out of the mesh. No foreground service, no relaying while backgrounded. |
| **Lost endpoints not auto-reconnected** | `onEndpointLost` only logs; reconnection depends on the peer being rediscovered. |
| **`Role.fromName` silent fallback** | Any unrecognized role string silently becomes `USER` — including corrupted prefs or malformed packets. |
| **Unused code paths** | `MessageDao.getAllIds` / `count` and `MessageRepository.clear()` are defined but never called. |

---

## 10. Planned but not built

Six packages exist as **folders containing only a `README.txt`** — they are the team's intended
modular structure, with ownership boundaries, but **zero implementation**:

| Package | Intended responsibility (per its `README.txt`) |
|---|---|
| `models/` | Shared pure data classes: `Signal`, `Priority` (CRITICAL/HIGH/NORMAL/LOW), `SignalCategory` (MEDICAL/RESCUE/RESOURCE/SAFETY), `AreaCluster`, `ResourceAllocation`, `SafeZone`. |
| `ai/` | On-device **Gemma 4** (LiteRT) integration: `GemmaClient` (sole model interface), `SignalClassifier`, `Translator` (regional language ↔ English), `ResourceAllocator`, `AreaSummarizer`, `PromptTemplates`. |
| `civilian/` | Civilian-role screens: signal-raising form, "my signals" status list. |
| `volunteer/` | Volunteer-role screens: task list assigned by authorities, respond/complete actions. |
| `authority/` | Authority-role screens: area dashboard, drill-down, signal detail, AI resource-plan view. |
| `map/` | Offline maps via **OSMDroid**: signal pins, safe zones, geographic clustering of signals. |

The READMEs sketch a much richer product than the shipped chat app: civilians raise **structured
emergency signals** (category, priority, location), an **on-device LLM** classifies/translates/
summarizes them, authorities see **clustered dashboards** and generate **resource-allocation plans**,
and volunteers receive **tasks** — all still over the same mesh. The common rule across every README
is *"do not touch `MeshManager.kt` or `MessageRepository.kt`"* — the mesh layer is meant to stay as
the stable transport while these features are layered on top.

**None of this exists in code.** Treat it as design intent only.

---

## 11. Relationship to the RN rewrite

This repo's `docs/knowledge/` also contains [`SYSTEM_KNOWLEDGE_SPEC.md`](./SYSTEM_KNOWLEDGE_SPEC.md),
which documents a **different, more advanced codebase** — a React Native + Expo app
(`offline-disaster-app/`, "DisasterMesh-New"). That code is **not on this branch**: per the git log
(`4d63513 Remove DisasterMesh-New from main (moved to disaster-mesh-new branch)`) it was relocated to
the `disaster-mesh-new` branch.

So the two documents describe two generations of the same idea:

| | `DisasterMesh-old` (this doc) | `offline-disaster-app` (`SYSTEM_KNOWLEDGE_SPEC.md`) |
|---|---|---|
| Stack | Native Android, Kotlin, Gradle | React Native + Expo, TypeScript |
| On `main`? | **Yes** — the only app present | No — moved to `disaster-mesh-new` branch |
| Domain entity | `Message` (role-tagged chat line) | `Signal` (structured emergency record) |
| Transport | Nearby Connections, BYTES only | Nearby Connections, BYTES **+ FILE** payloads |
| Flood control | UUID dedup, no TTL | Gossip layer with TTL + hop count |
| Persistence | Room, single `messages` table | SQLite, 8 tables (signals, accounts, caches, …) |
| AI | None (scaffolding only) | On-device Gemma via MediaPipe + keyword triage + RAG |
| Extra services | None | Hub WebSocket dashboard, model P2P transfer, battery watchdog |
| Maturity | Working prototype (chat) + empty module folders | Larger system, partly degraded without native bridges |

If you are building anything against the **current `main` branch**, the architecture in *this*
document is the ground truth. `SYSTEM_KNOWLEDGE_SPEC.md` applies to the `disaster-mesh-new` branch.

---

## 12. Quick reference — module map

| File | One-line responsibility |
|---|---|
| [MainActivity.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MainActivity.kt) | Main chat screen: view binding, role theme, permissions, mesh↔UI wiring, send |
| [RoleSetupActivity.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/RoleSetupActivity.kt) | First-run onboarding: role card selection + name → `UserSession` |
| [MeshManager.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MeshManager.kt) | Nearby Connections wrapper: advertise/discover, connection callbacks, flood broadcast, history sync |
| [MessageRepository.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/MessageRepository.kt) | Dedup gate + in-memory cache over Room (`seenIds`, `_messages`) |
| [Message.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Message.kt) | Domain + wire model; `toJson`/`fromJson`; `isVisibleTo` role rule |
| [Role.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/Role.kt) | `USER`/`VOLUNTEER`/`AUTHORITY` enum + display metadata |
| [UserSession.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/UserSession.kt) | `SharedPreferences`-backed local identity + `isSetup` gate |
| [adapter/MessageAdapter.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/adapter/MessageAdapter.kt) | `RecyclerView` adapter: chat bubble rendering, local/peer styling |
| [db/AppDatabase.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/AppDatabase.kt) | Room database singleton (`disaster_mesh.db`, v1) |
| [db/MessageDao.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/MessageDao.kt) | Room DAO: insert (IGNORE), getAll, ids, count, clear |
| [db/MessageEntity.kt](../../DisasterMesh-old/app/src/main/java/com/disastermesh/db/MessageEntity.kt) | Room `@Entity` for table `messages` + `Message` mappers |
| [AndroidManifest.xml](../../DisasterMesh-old/app/src/main/AndroidManifest.xml) | SDK-conditional permissions + two activity declarations |
| [app/build.gradle](../../DisasterMesh-old/app/build.gradle) | Module config + dependencies (Nearby, Room, AndroidX) |
| `res/layout/*.xml` | `activity_main`, `activity_role_setup`, `item_message` |
| `res/drawable/*.xml` | Bubble / badge / input / spinner shape drawables |
| `res/values/*.xml` | `colors` (dark palette), `strings`, `themes` |

---

*Generated from a full source inspection of `DisasterMesh-old/` on the `main` branch. Code citations
are authoritative; where the `README.txt` scaffolding or `SYSTEM_KNOWLEDGE_SPEC.md` describe more,
that is intent for other branches, not behavior of this code.*
