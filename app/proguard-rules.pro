# AURA ProGuard
-keepattributes *Annotation*
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
