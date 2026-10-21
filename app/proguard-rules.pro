# SpotAd release rules.

# Room generates implementations that are referenced reflectively from the database class.
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Keep line numbers so crash reports stay readable in Play Console.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose and Kotlin metadata are handled by their own consumer rules.
-dontwarn org.jetbrains.annotations.**
