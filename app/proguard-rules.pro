# gomobile (libbox) reaches Java through JNI by name: keep names and members.
-keep class go.** { *; }
-keep class gx.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Our platform callbacks are invoked from Go.
-keep class pro.wingerapp.vpn.** { *; }

# Keep shrinking, skip renaming: JNI + reflection stay safe.
-dontobfuscate

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.coroutines.**

# Annotations only: pulled in transitively through Tink (androidx.security-crypto).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.jspecify.**
