# Preserve line numbers for stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Accessibility Service and System Services
-keep public class com.example.accessibilitycaptions.CaptionAccessibilityService { *; }
-keep public class com.example.accessibilitycaptions.CaptionService { *; }
-keep public class com.example.accessibilitycaptions.MainActivity { *; }

# Keep data models
-keepclassmembers class com.example.accessibilitycaptions.MainUiState { *; }
-keepclassmembers class com.example.accessibilitycaptions.CaptionEvent** { *; }

# Kotlin Coroutines and Flows
-keepclassmembers class kotlinx.coroutines.** { *; }
