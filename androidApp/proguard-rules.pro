# WebRTC's native code looks up its Java classes and methods by name.
-keep class org.webrtc.** { *; }

# Readable stack traces in problem reports.
-keepattributes SourceFile,LineNumberTable
