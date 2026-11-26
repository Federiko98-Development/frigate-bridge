# WebRTC's native code looks up its Java classes and methods by name, including the JNI helper it is built
# with. Without these the app crashes as soon as the library is loaded.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
# A generated class the library does not ship, used only by a method the app never calls.
-dontwarn org.jni_zero.JniZeroJni

# Readable stack traces in problem reports.
-keepattributes SourceFile,LineNumberTable
