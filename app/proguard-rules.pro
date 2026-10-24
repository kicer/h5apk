# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留行号，便于线上问题定位；同时把源文件名混淆掉
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release 移除日志调用，避免符号与行为特征命中杀软规则
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# WebView JS 桥（VoiceBridge）：@JavascriptInterface 方法名不能被混淆，否则页面调不到
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
