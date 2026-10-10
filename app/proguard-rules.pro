-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.example.lifetrack.BuildConfig { *; }

-keep class com.example.lifetrack.SplashActivity { *; }
-keep class com.example.lifetrack.MainActivity { *; }
-keep class com.example.lifetrack.UsageDetailActivity { *; }

-keepclassmembers class com.example.lifetrack.DeepSeekClient$VerifyCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$DateAnalyzeCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$DateAnalyzeStreamCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$MultiDateAnalyzeCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$MultiDateAnalyzeStreamCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$SummaryCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$SummaryStreamCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$InsightTimeRangeCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$InsightAnswerCallback { *; }
-keepclassmembers class com.example.lifetrack.DeepSeekClient$CompressCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$VisionCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$VisionStreamCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$BatchVisionCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$BatchVisionStreamCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$VerifyCallback { *; }
-keepclassmembers class com.example.lifetrack.DoubaoClient$AsrCallback { *; }

-keep class com.example.lifetrack.DeepSeekClient$DateEntry { *; }
-keep class com.example.lifetrack.DeepSeekClient$SummaryEntry { *; }

-keep class com.example.lifetrack.DiaryDbHelper {
    public static final *;
    public static synchronized com.example.lifetrack.DiaryDbHelper getInstance(android.content.Context);
}
-keep class com.example.lifetrack.AnalyticsDao { *; }
-keep class com.example.lifetrack.DiaryDao { *; }
-keep class com.example.lifetrack.SummaryDao { *; }

-keep class net.sqlcipher.** { *; }
-keep class androidx.security.crypto.** { *; }

-keepclassmembers class * {
    public <init>(...);
}

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
