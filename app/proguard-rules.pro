# Hold High The Shop — R8 / ProGuard Keep Rules
# Preserve Firestore / Room data classes and Parcelables in release builds.

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

-keep class com.nevergrowold.holdhightheshop.data.** { *; }
-keep class com.nevergrowold.holdhightheshop.FreeDaysValidator { *; }
-keep class com.nevergrowold.holdhightheshop.FreeDaysValidator$CREATOR { *; }
-keep class com.nevergrowold.holdhightheshop.Listing { *; }
-keep class com.nevergrowold.holdhightheshop.Seller { *; }
-keep class com.nevergrowold.holdhightheshop.Exchange { *; }
-keep class com.nevergrowold.holdhightheshop.Request { *; }
-keep class com.nevergrowold.holdhightheshop.Issue { *; }
-keep class com.nevergrowold.holdhightheshop.FreeTime { *; }
-keep class com.nevergrowold.holdhightheshop.Availability { *; }
-keep class com.nevergrowold.holdhightheshop.DealEvent { *; }

# Keep XML android:onClick handlers across all Activities
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
