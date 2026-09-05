# R8 rules for UNIQUE.
#
# Deliberately short. A virtualization engine is tempting to blanket-keep, and a blanket
# keep would hide exactly the breakage worth finding — so each rule below names something
# whose *identity* is load-bearing, and nothing else is protected.
#
# The release build is verified by running the acceptance suite against it, not by
# inspection. If a rule here is wrong the suite goes red, which is the only reason to
# trust a list like this at all.

# ---------------------------------------------------------------------------------------
# The stub pool.
#
# Stub classes are coupled to their names three ways at once: the generated manifest
# declares them, StubRouter *builds* those names as strings, and VirtualServiceRouter
# parses a name back into a slot and an index. R8 keeps manifest-declared classes, but the
# string coupling is the fragile part and it is worth stating rather than relying on a
# side effect of manifest parsing.
-keep class com.unique.stub.** { *; }

# ---------------------------------------------------------------------------------------
# JNI.
#
# Every native entry point is resolved from C by its fully-qualified Java name
# (Java_com_unique_core_nativebridge_UniqueNative_nativePageSize and friends). A rename is
# not a link error — it is UnsatisfiedLinkError at the moment a virtual app first touches
# native code, which is the least convenient place to discover it.
#
# proguard-android-optimize.txt already carries a -keepclasseswithmembernames rule for
# native methods; this is stated explicitly because the consequence of losing it is severe
# and non-obvious.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.unique.core.nativebridge.UniqueNative { *; }

# ---------------------------------------------------------------------------------------
# Enums crossing the process boundary.
#
# VirtualLaunchParams writes enum *names* into an Intent and reads them back with
# valueOf() in another process. Obfuscating those names turns every cross-process launch
# into a silent fall-back to the default value.
-keepclassmembers enum com.unique.core.common.** { *; }
-keepclassmembers enum com.unique.core.vam.** { *; }

# ---------------------------------------------------------------------------------------
# Room's generated implementation, found by name at runtime.
-keep class com.unique.core.vpm.db.** { *; }

# ---------------------------------------------------------------------------------------
# The bridge's method names are the Flutter method-channel contract: the Dart side sends
# them as strings. The channel itself is a `when` over string literals, so only the data
# classes crossing it matter.
-keep class com.unique.app.bridge.** { *; }
