# Regole R8/ProGuard per VideoB.
# Flutter genera le sue regole per i plugin; qui solo librerie manuali (OkHttp,
# media3), che di solito bastano alle regole consumer incluse nelle AAR ma
# teniamo kept espliciti i punti riflessivi noti.

# OkHttp: leggere i nomi degli header via riflessione su kotlin.Metadata
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# media3 (ExoPlayer): farsi dare le implementazioni via riflessione
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Jsoup-free: StreamExtractor usa regex, nessuna regola aggiuntiva necessaria.
