# R8 rules that apply to the instrumentation APK only.
#
# Kept out of proguard-rules.pro on purpose: nothing here is about UNIQUE, and a product
# rule file that quietly carries the test framework's problems is a rule file nobody can
# read.
#
# Truth pulls in Error Prone's annotations, which reference javax.lang.model — a
# compile-time-only API that is not on Android. R8 is right that the classes are missing
# and right that it does not matter: the annotations are never read at runtime.
-dontwarn javax.lang.model.element.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**

# The test infrastructure is kept whole.
#
# Shrinking it buys nothing — the instrumentation APK is never shipped — and costs a great
# deal: R8 removed androidx.tracing.Trace, which AndroidJUnitRunner loads reflectively, and
# the entire suite died in onCreate before a single test ran:
#
#   NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
#       at androidx.test.runner.AndroidJUnitRunner.onCreate
#
# A failure like that says nothing about the build under test, which is the one thing this
# run exists to establish.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class com.google.common.truth.** { *; }
-dontwarn androidx.test.**

# The suite finds its tests by name through reflection, so their identity is load-bearing
# in exactly the way the stub pool's is.
-keep class com.unique.app.VirtualLaunchTest { *; }
-keep class com.unique.app.TestFileProvider { *; }
