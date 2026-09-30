# QALQON release ProGuard/R8 rules.
#
# Phase 14 status: R8 minification is DELIBERATELY NOT ENABLED
# (isMinifyEnabled = false in app/build.gradle.kts). Enabling it is a release
# decision that must be verified on a real device (Hilt, Room, TFLite, ML Kit and
# model-asset loading all have runtime/reflective surfaces), so it is not turned
# on blindly here.
#
# The previous content was a blanket `-keepclassmembers class ** { *; }`, which
# keeps every member of every class and would have silently defeated shrinking and
# obfuscation the moment minification was enabled. It was removed.
#
# No project-specific keep rules are required today: Hilt, Room and ML Kit ship
# their own consumer ProGuard rules, and TFLite's Java API is referenced directly.
# If minification is enabled later, add the minimum rules needed for the failing
# surfaces here and verify with a release build AND device testing.
