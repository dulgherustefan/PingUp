# Crash stack traces keep line numbers; the release mapping file turns names back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNA's native dispatcher finds its classes, fields and callbacks by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# lazysodium binds its native methods by name (Native.register).
-keep class com.goterl.lazysodium.** { *; }
