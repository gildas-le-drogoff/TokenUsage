-repackageclasses ''
-allowaccessmodification
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,AnnotationDefault
-renamesourcefileattribute SourceFile
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-dontnote kotlinx.serialization.AnnotationsKt
-keep,allowobfuscation interface fr.gildasledrogoff.tokenusage.core.network.** {
    <methods>;
}
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }

# R8 non-determinism (F-Droid reproducible build): when emitAbort$1.<init> is
# inlined into emitAbort, the strengthened type of its Continuation parameter
# (Continuation vs ContinuationImpl) depends on the build tree path. Kept
# (not optimized) members make the output identical across checkouts.
-keep,allowobfuscation class kotlinx.coroutines.flow.FlowKt__LimitKt* { *; }
