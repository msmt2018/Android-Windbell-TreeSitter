-keep class com.zerostudio.idea.treesitter.** { *; }

-keep class com.zerostudio.idea.treesitter.internal.NativeObjectFactory {
    static *** create*(...);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.zerostudio.idea.treesitter.** {
    native <methods>;
    *;
}
