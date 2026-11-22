-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-optimizationpasses 5
-allowaccessmodification

-keep class com.truelight.ime.TrueLightIME { *; }
-keepclassmembers class * extends android.inputmethodservice.InputMethodService {
   public <init>(...);
}
