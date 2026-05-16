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
        DirectMessageEntity::class
    ],
    version = 3,
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

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
