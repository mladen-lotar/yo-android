# R8 configuration for release builds.
#
# The app deliberately has no reflective JSON mapping - every request and response in
# data/remote/YoBackendApi.kt is built and read field-by-field with org.json - so there are no
# model classes whose field names must survive obfuscation. That is why this file is short.
# Room, Hilt, Firebase, Play Services and androidx.credentials all ship their own consumer rules.

# Keep enough of each stack frame to make a Play Console crash report readable. Without these a
# crash arrives as obfuscated, line-less frames; with them, the mapping file uploaded alongside
# the bundle deobfuscates it cleanly.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
