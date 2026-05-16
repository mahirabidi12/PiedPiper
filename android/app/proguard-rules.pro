-keepattributes *Annotation*

# ── App domain models & wire types ────────────────────────────────────────────
-keep class com.disastermesh.app.model.** { *; }
-keep class com.disastermesh.app.db.entities.** { *; }
-keep class com.disastermesh.app.mesh.MeshPacket { *; }

# ── Room — DAOs, database, and generated implementations ─────────────────────
# Entity classes are already kept above.  DAOs are accessed by Room's generated
# _Impl classes via the class name; stripping them breaks the database at runtime.
-keep interface com.disastermesh.app.db.dao.** { *; }
-keep class com.disastermesh.app.db.AppDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── LiteRT-LM / Google AI Edge ────────────────────────────────────────────────
# The Engine, EngineConfig, ConversationConfig, and Backend classes are loaded
# by the LiteRT runtime using reflection.  Obfuscating them causes silent
# failure at model load time with no useful stack trace.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ── AI language enum (accessed by name in language-preference serialisation) ──
-keep enum com.disastermesh.app.ai.SurvivalLanguage { *; }

# ── Strip all android.util.Log calls from release builds ─────────────────────
# Requires minifyEnabled true in the release buildType.
# -assumenosideeffects tells R8 the methods have no observable side effects
# and can be removed entirely — call sites, string concatenations, and all.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
