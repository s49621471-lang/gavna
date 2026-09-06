// Host-side test for the guest's view of /proc.
//
// The lines below are real ones, taken from the eleventh phone log's `:vapp0` — the
// process Standoff 2 ran in. Every one of them names UNIQUE, and reading them costs an
// app one `fopen` and no permission at all. Build and run with tools/native-test/run.sh.

#include "../../core/native/src/main/cpp/proc_view.h"

#include <cstdio>
#include <string>
#include <vector>

static int failures = 0;
static int checks = 0;

static void expect_eq(const std::string& actual, const std::string& expected, const char* what) {
    ++checks;
    if (actual != expected) {
        std::printf("FAIL  %s\n      expected: %s\n      actual:   %s\n",
                    what, expected.c_str(), actual.c_str());
        ++failures;
    }
}

static void expect_true(bool v, const char* what) {
    ++checks;
    if (!v) { std::printf("FAIL  %s\n", what); ++failures; }
}

static void expect_false(bool v, const char* what) {
    ++checks;
    if (v) { std::printf("FAIL  %s (expected false)\n", what); ++failures; }
}

static const char* kGuest = "com.axlebolt.standoff2";
static const char* kPublicApk = "/data/app/~~Yk8xQ2h5bVNkQ0FoUUJRSA/com.axlebolt.standoff2-VGhlU2lsZW5jZUlzVGhl";
static const char* kHostApk = "/data/app/~~eOlB8_n2llAc3Bs06uk3yQ==/com.unique-LcSgGPsGIC2VRZTEGkF33g==";

static unique::ProcView make_view() {
    const std::string root = "/data/user/0/com.unique/files/virtual";
    const std::string apk = root + "/apk/" + kGuest + "/203908";
    const std::string data = root + "/users/0/data/" + kGuest;
    const std::string ext = root + "/users/0/sdcard";
    const std::string publicApk = kPublicApk;

    unique::ProcView view;
    view.set({
        // The guest's own code, which lives in UNIQUE's shared APK directory.
        {apk + "/lib/arm64-v8a", publicApk + "/lib/arm64"},
        {apk, publicApk},
        // The guest's own data.
        {data, std::string("/data/user/0/") + kGuest},
        // What it calls /sdcard.
        {ext, "/storage/emulated/0"},
        // UNIQUE's own install, which is mapped into every `:vappN` because that is
        // whose process it is. Presented as more of the guest's own code.
        {kHostApk, publicApk},
        // Anything else of UNIQUE's under its private directory.
        {"/data/user/0/com.unique", std::string("/data/user/0/") + kGuest},
    });
    return view;
}

int main() {
    const auto view = make_view();
    std::string out;

    // ---- which files the view answers -------------------------------------------
    expect_true(unique::ProcView::covers("/proc/self/maps", 5432), "self/maps");
    expect_true(unique::ProcView::covers("/proc/self/smaps", 5432), "self/smaps");
    expect_true(unique::ProcView::covers("/proc/5432/maps", 5432), "own pid maps");
    expect_true(unique::ProcView::covers("/proc/self/task/5466/maps", 5432), "own thread maps");
    expect_true(unique::ProcView::covers("/proc/5432/task/5466/smaps", 5432), "own pid thread smaps");

    expect_false(unique::ProcView::covers("/proc/1/maps", 5432), "another process's maps");
    expect_false(unique::ProcView::covers("/proc/self/status", 5432), "status is not covered");
    expect_false(unique::ProcView::covers("/proc/self/cmdline", 5432), "cmdline is not covered");
    expect_false(unique::ProcView::covers("/proc/self/mapsx", 5432), "a longer name is not maps");
    expect_false(unique::ProcView::covers("/proc/self/task/abc/maps", 5432), "a non-numeric task");
    expect_false(unique::ProcView::covers("/data/user/0/x/maps", 5432), "a file called maps");
    expect_false(unique::ProcView::covers(nullptr, 5432), "a null path");

    // ---- the lines the eleventh run actually had ---------------------------------
    expect_eq(view.rewrite(
        "7b30112000-7b3aa8e000 r-xp 00000000 fd:05 1712 "
        "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/"
        "lib/arm64-v8a/libunity.so\n"),
        std::string("7b30112000-7b3aa8e000 r-xp 00000000 fd:05 1712 ") + kPublicApk +
        "/lib/arm64/libunity.so\n",
        "the guest's own library reads as installed");

    expect_eq(view.rewrite(
        "7b2c4a1000-7b2f9e3000 r--p 00000000 fd:05 1701 "
        "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/base.apk\n"),
        std::string("7b2c4a1000-7b2f9e3000 r--p 00000000 fd:05 1701 ") + kPublicApk + "/base.apk\n",
        "the guest's own APK reads as installed");

    expect_eq(view.rewrite(
        std::string("7a11000000-7a12000000 r--p 00000000 fd:05 900 ") + kHostApk + "/base.apk\n"),
        std::string("7a11000000-7a12000000 r--p 00000000 fd:05 900 ") + kPublicApk + "/base.apk\n",
        "UNIQUE's own APK reads as the guest's");

    expect_eq(view.rewrite(
        std::string("7a13000000-7a14000000 r-xp 00000000 fd:05 901 ") + kHostApk +
        "/lib/arm64/libunique_native.so\n"),
        std::string("7a13000000-7a14000000 r-xp 00000000 fd:05 901 ") + kPublicApk +
        "/lib/arm64/libunique_native.so\n",
        "UNIQUE's own library reads as one of the guest's");

    expect_eq(view.rewrite(
        "7c00000000-7c00001000 rw-s 00000000 fd:05 2001 "
        "/data/user/0/com.unique/files/virtual/users/0/data/com.axlebolt.standoff2/"
        "files/prefs.xml\n"),
        "7c00000000-7c00001000 rw-s 00000000 fd:05 2001 "
        "/data/user/0/com.axlebolt.standoff2/files/prefs.xml\n",
        "the guest's own data reads as installed");

    expect_eq(view.rewrite(
        "7c10000000-7c20000000 r--s 00000000 fd:05 2002 "
        "/data/user/0/com.unique/files/virtual/users/0/sdcard/Android/obb/"
        "com.axlebolt.standoff2/main.203908.com.axlebolt.standoff2.obb\n"),
        "7c10000000-7c20000000 r--s 00000000 fd:05 2002 "
        "/storage/emulated/0/Android/obb/com.axlebolt.standoff2/"
        "main.203908.com.axlebolt.standoff2.obb\n",
        "the expansion file reads as being on the sdcard");

    // ---- what must be left exactly alone ----------------------------------------
    const std::string platform =
        "7b0e000000-7b0e800000 r-xp 00000000 fd:03 100 /apex/com.android.art/lib64/libart.so\n"
        "7b0f000000-7b0f100000 r-xp 00000000 fd:03 101 /system/lib64/libandroid_runtime.so\n"
        "7fd4e21000-7fd4ea2000 rw-p 00000000 00:00 0   [stack]\n"
        "7b20000000-7b21000000 rw-p 00000000 00:00 0   [anon:dalvik-main space]\n";
    expect_eq(view.rewrite(platform), platform, "platform mappings are untouched");

    // A whole file at once, in order, with a mapping that has no path at all.
    const std::string mixed =
        "7b0e000000-7b0e800000 r-xp 00000000 fd:03 100 /apex/com.android.art/lib64/libart.so\n"
        "7b30112000-7b3aa8e000 r-xp 00000000 fd:05 1712 "
        "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/"
        "lib/arm64-v8a/libfmod.so\n"
        "7fd4e21000-7fd4ea2000 rw-p 00000000 00:00 0 \n";
    expect_eq(view.rewrite(mixed),
        std::string("7b0e000000-7b0e800000 r-xp 00000000 fd:03 100 "
                    "/apex/com.android.art/lib64/libart.so\n"
                    "7b30112000-7b3aa8e000 r-xp 00000000 fd:05 1712 ") + kPublicApk +
        "/lib/arm64/libfmod.so\n"
        "7fd4e21000-7fd4ea2000 rw-p 00000000 00:00 0 \n",
        "a whole file keeps its line count and order");

    // ---- rules must not capture a longer name -----------------------------------
    unique::ProcView boundary;
    boundary.set({{"/data/user/0/com.unique", "/data/user/0/com.guest"}});
    expect_eq(boundary.rewrite("/data/user/0/com.uniquely/files\n"),
              "/data/user/0/com.uniquely/files\n",
              "a longer package name is not a match");
    expect_eq(boundary.rewrite("/data/user/0/com.unique/files\n"),
              "/data/user/0/com.guest/files\n",
              "the package name itself is");
    expect_eq(boundary.rewrite("x /data/user/0/com.unique\n"),
              "x /data/user/0/com.guest\n",
              "a path at the end of a line");

    // ---- a path is rewritten once, never twice ----------------------------------
    // The second rule's `from` appears inside the first rule's `to`. Rewriting per rule
    // instead of per position would apply it again and produce nonsense.
    unique::ProcView chained;
    chained.set({
        {"/a/b", "/c"},
        {"/c", "/d"},
    });
    expect_eq(chained.rewrite("/a/b/x\n"), "/c/x\n", "output is not re-scanned");
    expect_eq(chained.rewrite("/c/x\n"), "/d/x\n", "but input still is");

    // ---- readlink answers --------------------------------------------------------
    const std::string fd = std::string(kHostApk) + "/base.apk";
    expect_true(view.rewrite_path(fd.c_str(), fd.size(), out), "an fd symlink is rewritten");
    expect_eq(out, std::string(kPublicApk) + "/base.apk", "the fd symlink's target");

    const std::string exe = "/system/bin/app_process64";
    expect_false(view.rewrite_path(exe.c_str(), exe.size(), out),
                 "a path naming nothing of UNIQUE's is left alone");

    // ---- an unconfigured view is a no-op ----------------------------------------
    unique::ProcView empty;
    expect_true(empty.empty(), "a new view is empty");
    expect_eq(empty.rewrite("/data/user/0/com.unique/x\n"), "/data/user/0/com.unique/x\n",
              "an empty view rewrites nothing");
    expect_false(empty.rewrite_path("/x", 2, out), "an empty view leaves paths alone");

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
