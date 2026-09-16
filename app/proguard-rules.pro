# Preserve line numbers for stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Accessibility Service and System Services
-keep public class com.blinkboard.accessibilitycaptions.CaptionAccessibilityService { *; }
-keep public class com.blinkboard.accessibilitycaptions.CaptionService { *; }
-keep public class com.blinkboard.accessibilitycaptions.MainActivity { *; }

# Keep data models
-keepclassmembers class com.blinkboard.accessibilitycaptions.MainUiState { *; }
-keepclassmembers class com.blinkboard.accessibilitycaptions.CaptionEvent** { *; }

# Kotlin Coroutines and Flows
-keepclassmembers class kotlinx.coroutines.** { *; }
