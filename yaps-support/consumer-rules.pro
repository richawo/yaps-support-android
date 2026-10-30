# Consumer R8/ProGuard rules for ai.yaps.support. Applied automatically to apps that depend on the SDK.

# Wire models are (de)serialized with kotlinx.serialization. The library's generated serializers are
# looked up through the Companion; keep them (and the annotations R8 needs to see) for our package.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-if @kotlinx.serialization.Serializable class ai.yaps.support.**
-keepclassmembers class ai.yaps.support.** {
    static **$* *;
    *** Companion;
}
-if @kotlinx.serialization.Serializable class ai.yaps.support.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.yaps.support.internal.**$$serializer { *; }
-keepclassmembers enum ai.yaps.support.internal.** {
    <fields>;
    **[] values();
    ** valueOf(java.lang.String);
}

# WorkManager instantiates the outbox worker by class name, possibly in a cold process.
-keep class ai.yaps.support.internal.outbox.OutboxWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Public API is meant to be called from Java and Kotlin hosts alike.
-keep public class ai.yaps.support.YapsSupport { public *; }
-keep public class ai.yaps.support.SupportActivity
