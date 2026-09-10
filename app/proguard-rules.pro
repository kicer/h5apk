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

# 本项目未使用 addJavascriptInterface，若后续新增再按需 keep：
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
