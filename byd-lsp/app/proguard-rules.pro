# 模块自身不参与混淆；微信内部类通过反射访问，保持 Hook 侧名称稳定
-keep class xiaojw.byd.** { *; }
-keepattributes *Annotation*

# WebUI 通过 addJavascriptInterface 暴露的方法只能在 JS 里被调用，
# R8 看不到调用点会把它们删掉 —— 必须显式保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

-dontwarn de.robv.android.xposed.**
