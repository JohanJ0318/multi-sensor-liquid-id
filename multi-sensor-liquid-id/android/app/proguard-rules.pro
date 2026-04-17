# Proguard rules for Liquid ID app

# Keep USB serial library
-keep class com.hoho.android.usbserial.** { *; }

# Keep MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Keep data models (used in serialization)
-keep class com.group17.liquidid.data.model.** { *; }
