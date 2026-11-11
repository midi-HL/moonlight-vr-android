# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Google Cardboard SDK JNI surface: obfuscating a class/method name lets
# System.loadLibrary succeed but makes every subsequent JNI call fail on device.
-keep class com.google.cardboard.sdk.** { *; }
-keep class com.google.cardboard.proto.** { *; }
# VR preference keys are persisted by name; keep the whole VR package so R8
# cannot rename enum constants or the JNI/GL glue.
-keep class com.limelight.vr.** { *; }

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# Shizuku starts this service by class name in another process.
-keep class com.limelight.services.ShellService { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell$Stub { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell$Stub$Proxy { *; }
