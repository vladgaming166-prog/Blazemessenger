# Domain models are written and read by Firebase with reflection.
-keep class ro.blazemessenger.app.domain.model.** { *; }

-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
