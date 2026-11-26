# kotlinx.serialization: keep generated serializers (the plugin ships most rules; these cover companions).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.github.blublub.tvcal.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.blublub.tvcal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
