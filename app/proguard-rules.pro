# JGit is reflection/ServiceLoader-heavy; keep it whole (size cost is acceptable for correctness).
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# JGit's optional logging/auth deps that aren't bundled.
-dontwarn org.slf4j.**
-dontwarn javax.crypto.spec.**
-dontwarn org.ietf.jgss.**

# OkHttp/Okio ship consumer rules; silence remaining platform warnings.
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx-serialization ships its own R8 rules; keep generated serializers' companions safe.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Tink (inside androidx.security-crypto) references compile-only errorprone/jsr305 annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
