# JNI entry points are resolved by name from libsensoriumlsl.so.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.markspan.sensorium.lsl.LslNative { *; }
