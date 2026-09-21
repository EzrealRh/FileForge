# PDFBox(android 分支) 用反射装配过滤器和解码器，包名是 com.tom_roush 前缀
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# JPEG2000 解码是可选外挂，没集成 gemalto 库，PDFBox 只是引用它
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-dontwarn java.awt.**
-dontwarn org.apache.commons.logging.**
