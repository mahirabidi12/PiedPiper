package com.disastermesh.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.disastermesh.app.db.dao.*
import com.disastermesh.app.db.entities.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        SignalEntity::class,
        ChatMessageEntity::class,
        PeerEntity::class,
        SeenPacketEntity::class,
        SyncLogEntity::class,
        InventoryEntity::class,
        DirectMessageEntity::class,
        AiSessionEntity::class,
        AiMessageEntity::class,
        AuditLogEntity::class,
        CriticalPoiEntity::class,
        SafeZoneEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun signalDao(): SignalDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun peerDao(): PeerDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun directMessageDao(): DirectMessageDao
    abstract fun aiSessionDao(): AiSessionDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun criticalPoiDao(): CriticalPoiDao
    abstract fun safeZoneDao(): SafeZoneDao

    companion object {
        private const val DB_NAME = "disaster_mesh.db"

        @Volatile private var instance: AppDatabase? = null

        // ── Schema migrations ─────────────────────────────────────────────────
        // Each migration must be listed in addMigrations() below.
        // Never drop a migration — they form a chain Room walks for any upgrade path.
        // Use `exportSchema = true` and commit the generated JSON files to version control
        // so future developers can diff schema changes exactly.

        // v1 → v2: Added AI classification columns to the signals table.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN ai_classified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE signals ADD COLUMN ai_summary TEXT")
            }
        }

        // v2 → v3: Added the direct_messages table for peer-to-peer DM threads.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `direct_messages` (
                        `id`               TEXT    NOT NULL,
                        `thread_id`        TEXT    NOT NULL,
                        `sender_node_id`   TEXT    NOT NULL,
                        `sender_name`      TEXT    NOT NULL,
                        `recipient_node_id` TEXT   NOT NULL,
                        `text`             TEXT    NOT NULL,
                        `created_at`       INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_direct_messages_thread_id` ON `direct_messages` (`thread_id`)")
            }
        }

        // v3 → v4: Added the on-device AI chat history layer
        // (ai_sessions + ai_messages with pinned/archived flags).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_sessions` (
                        `id`               TEXT    NOT NULL,
                        `topic`            TEXT    NOT NULL,
                        `title`            TEXT    NOT NULL,
                        `created_at`       INTEGER NOT NULL,
                        `last_message_at`  INTEGER NOT NULL,
                        `message_count`    INTEGER NOT NULL DEFAULT 0,
                        `language_code`    TEXT    NOT NULL DEFAULT 'en',
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_topic` ON `ai_sessions` (`topic`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_last_message_at` ON `ai_sessions` (`last_message_at`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_messages` (
                        `id`         TEXT    NOT NULL,
                        `session_id` TEXT    NOT NULL,
                        `is_user`    INTEGER NOT NULL,
                        `text`       TEXT    NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `pinned`     INTEGER NOT NULL DEFAULT 0,
                        `archived`   INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_session_id` ON `ai_messages` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_created_at` ON `ai_messages` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_pinned` ON `ai_messages` (`pinned`)")
            }
        }

        // v4 → v5: Added timestamp/tracking columns to inventory and created audit_log table.
        // The ALTER TABLEs are guarded — devices that ran the pre-release v4 build already
        // have these columns and would crash with "duplicate column name" otherwise.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE inventory ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE inventory ADD COLUMN updated_by TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE inventory ADD COLUMN updated_by_name TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE inventory ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0"
                ).forEach { sql -> try { db.execSQL(sql) } catch (_: Exception) {} }
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `audit_log` (
                        `id`           TEXT    NOT NULL,
                        `entity_type`  TEXT    NOT NULL,
                        `entity_id`    TEXT    NOT NULL,
                        `entity_label` TEXT    NOT NULL,
                        `action`       TEXT    NOT NULL,
                        `actor_node_id` TEXT   NOT NULL,
                        `actor_name`   TEXT    NOT NULL,
                        `detail`       TEXT    NOT NULL,
                        `created_at`   INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_entity_type` ON `audit_log` (`entity_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_created_at` ON `audit_log` (`created_at`)")
            }
        }

        // v5 → v6: Added multi-volunteer + instructions fields and critical POIs.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE signals ADD COLUMN instructions TEXT",
                    "ALTER TABLE signals ADD COLUMN volunteer_ids TEXT",
                    "ALTER TABLE signals ADD COLUMN volunteer_names TEXT"
                ).forEach { sql -> try { db.execSQL(sql) } catch (_: Exception) {} }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signals_volunteer_ids` ON `signals` (`volunteer_ids`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `critical_pois` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `amenity_type` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `is_verified` INTEGER NOT NULL DEFAULT 0,
                        `operational_status` TEXT NOT NULL DEFAULT 'OPERATIONAL',
                        `updated_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_critical_pois_amenity_type` ON `critical_pois` (`amenity_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_critical_pois_latitude_longitude` ON `critical_pois` (`latitude`, `longitude`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_critical_pois_updated_at` ON `critical_pois` (`updated_at`)")
            }
        }

        // v6 → v7: Added persisted safe zones for role-based map overlays and mesh sync.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `safe_zones` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `radius_meters` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_zones_created_at` ON `safe_zones` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_safe_zones_latitude_longitude` ON `safe_zones` (`latitude`, `longitude`)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                instance?.seedInventory()
                            }
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }

    private suspend fun seedInventory() {
        if (inventoryDao().count() > 0) return
        listOf(
            InventoryEntity("water",   "Water Bottles", "bottles", 120),
            InventoryEntity("medkit",  "Medkits",       "kits",     34),
            InventoryEntity("food",    "Food Packs",    "packs",    56),
            InventoryEntity("rope",    "Rope Rolls",    "rolls",     8),
            InventoryEntity("blanket", "Blankets",      "pcs",      45),
        ).forEach { inventoryDao().upsert(it) }
    }
}
