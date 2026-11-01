# PdfBox-Android: optional JPEG 2000 decoder and desktop-only classes it can reference.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.bind.**
# Fonts, encodings and CMaps are looked up by name at run time.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
