# OwnTV release keep rules (R8). Each rule guards a runtime lookup that R8 can't see
# statically — trim only with a full on-device regression (sync, both engines, backup).

# --- Crash reports: keep readable stack traces (mapping.txt still needed for names) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- libmpv JNI: native code resolves MPVLib and its event callbacks by name ---
-keep class dev.jdtech.mpv.** { *; }

# --- WorkManager: workers are re-instantiated by FQCN string (KoinWorkerFactory matches
#     workerClassName against ::class.java.name, and WorkManager persists the name in its DB
#     across app updates) ---
-keep class * extends androidx.work.ListenableWorker

# --- Room: Room.getGeneratedImplementation() derives this exact class name and loads it
#     reflectively. Keep the generated database entry point in optimized release builds. ---
-keep class tv.own.owntv.core.database.OwnTVDatabase_Impl { *; }

# --- Persisted enum names: DataStore prefs, backup JSON ("t" = MediaType.name), and
#     per-setting modes (ZoomMode, ThemeMode, StartupMode, AnimationLevel, ResumeMode,
#     EpgAutoRefresh, PlaylistAutoRefresh, ...) round-trip through Enum.name/valueOf.
#     Renaming a constant would silently reset settings and break old backups. ---
-keep enum tv.own.owntv.** { *; }

# --- OwnTVnew: player internals reached by name (translate/SubtitleWatch.kt MpvAccess,
#     translate/SubtitleKinds.kt). Each lookup fails quietly, but would then do nothing. ---
-keepclassmembers class tv.own.owntv.player.MpvPlaybackEngine { *** p; }
-keepclassmembers class tv.own.owntv.player.OwnTVPlayer {
    *** mpv;
    void setCcSoftwareOverride(boolean);
}
-keepclassmembers class tv.own.owntv.player.LivePreviewEngine { *** player; }

# --- OwnTVnew: ML Kit (translate, language id, text recognition). Its internal protobuf classes are
#     read by name; with them shrunk, language detection failed silently ("Detecting language…"). ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# --- OwnTVnew: shrink and rename, but no code rewriting. Optimisation passes are where release-only
#     behaviour changes come from (lip sync drifted on both players in the first release build); the
#     smoothness gain comes from a non-debuggable build plus the baseline profile anyway. ---
-dontoptimize
