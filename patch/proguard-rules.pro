-keep class com.pishi.hotfix.**{*;}
-keep class meituan.robust.**{*;}
-keep class com.google.gson.**{*;}
-keepattributes *Annotation*
-keepclassmembers class **{
public static com.pishi.hotfix.ChangeQuickRedirect *;
}

