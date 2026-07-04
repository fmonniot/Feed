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

##---------------------------------------------------------------------------
## kotlinx.serialization (canonical rules from the official README).
## Ktor/Room/Compose/Coil/OkHttp ship their own consumer ProGuard rules, so
## the only reflection surface we must protect ourselves is the serializers the
## kotlin-serialization plugin generates for our @Serializable data models.
##---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep the `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

##---------------------------------------------------------------------------
## Belt-and-suspenders for our own API models: keep the generated $$serializer
## classes and the hand-written SyncResponseSerializer referenced only through
## a @Serializable(with = ...) annotation.
##---------------------------------------------------------------------------
-keep,includedescriptorclasses class eu.monniot.feed.shared.api.**$$serializer { *; }
-keepclassmembers class eu.monniot.feed.shared.api.** {
    *** Companion;
}
-keepclasseswithmembers class eu.monniot.feed.shared.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class eu.monniot.feed.shared.api.SyncResponseSerializer { *; }