# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep SMS/MMS receiver classes
-keep class com.smssync.app.SmsReceiver { *; }
-keep class com.smssync.app.MmsReceiver { *; }
-keep class com.smssync.app.MainActivity { *; }


