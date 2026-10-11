# The JSON stores (settings, friends, chat, incidents) and venue.json are read through these classes.
# Keeping them whole, with their generated serializers and companions, keeps them stable under R8.
-keep @kotlinx.serialization.Serializable class ro.safetyplease.core.** { *; }
-if @kotlinx.serialization.Serializable class ro.safetyplease.core.**
-keep class ro.safetyplease.core.<1>$$serializer { *; }
-if @kotlinx.serialization.Serializable class ro.safetyplease.core.**
-keep class ro.safetyplease.core.<1>$Companion { *; }
