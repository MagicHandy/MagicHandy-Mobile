# Room resolves this generated implementation by the public database class name and invokes its no-arg constructor.
-keepnames class com.magichandy.mobile.platform.storage.PlatformDatabase
-keep,allowoptimization class com.magichandy.mobile.platform.storage.PlatformDatabase_Impl { public <init>(); }

# kotlinx.serialization's consumer rules retain companion accessors. The stores call generated serializers
# directly. checkReleaseContents verifies the stored models, journal, chat and persona serializers in DEX.
