# Giu code khi minify ban release.
-keep class com.example.webtts.** { *; }
# V2.2: giu NewPipeExtractor (giong youtube-lite).
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
