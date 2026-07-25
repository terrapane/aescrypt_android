# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# proguard-rules.pro

# Preserve native method linkage across the app
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Preserve the NativeBridge singleton and all its members
-keep class com.terrapane.aescrypt.NativeBridge { *; }
-keepclassmembers class com.terrapane.aescrypt.NativeBridge { *; }

# Preserve the NativeProgress data class, its fields, and its constructors for JNI
-keep class com.terrapane.aescrypt.NativeProgress { *; }
-keepclassmembers class com.terrapane.aescrypt.NativeProgress { *; }