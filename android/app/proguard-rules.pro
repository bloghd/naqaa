# Download engine (yt-dlp wrapper) and its helpers
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn javax.annotation.**

# ONNX Runtime calls back into Java from native code
-keep class ai.onnxruntime.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
