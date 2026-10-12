# Savant keeps no reflection-based serialization yet; the release build is unminified.
# When R8 is turned on, keep the parser plugin model (JSON round-trips through it).
-keepclassmembers class dev.savant.plugins.model.** { *; }
