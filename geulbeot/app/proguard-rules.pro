# The document engine is plain Kotlin with no reflection, so the default rules suffice.
# Keep the model classes' names readable in crash reports without keeping their members.
-keepnames class kr.geulbeot.hwp.model.** { *; }

# SAX is used for OWPML parsing; the platform implementation is resolved by name.
-dontwarn javax.xml.**
-keep class org.xml.sax.** { *; }
