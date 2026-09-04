// Host-side test for the native redirection table.
//
// Runs on the build machine with the system compiler — no device, no NDK — because the
// redirection rules are pure logic and are the piece most likely to corrupt a user's data
// if they are wrong. Build and run with tools/native-test/run.sh.

#include "../../core/native/src/main/cpp/redirect_table.h"

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
    if (v) { std::printf("FAIL  %s (expected no redirect)\n", what); ++failures; }
}

static unique::RedirectTable make_table(int vuid) {
    const std::string root = "/data/user/0/com.unique/files/virtual";
    const std::string data = root + "/users/" + std::to_string(vuid) + "/data/com.example.sample";
    const std::string ext = root + "/users/" + std::to_string(vuid) + "/sdcard";
    unique::RedirectTable t;
    t.set({
        {"/data/data/com.example.sample", data},
        {"/data/user/0/com.example.sample", data},
        {"/data/user_de/0/com.example.sample", data},
        {"/sdcard", ext},
        {"/storage/emulated/0", ext},
        {"/storage/self/primary", ext},
        {"/mnt/sdcard", ext},
    });
    return t;
}

int main() {
    const auto t0 = make_table(0);
    const auto t1 = make_table(1);
    std::string out;

    // Hard-coded data paths land in the right instance.
    expect_true(t0.redirect("/data/data/com.example.sample/databases/m.db", out), "data path matches");
    expect_eq(out, "/data/user/0/com.unique/files/virtual/users/0/data/com.example.sample/databases/m.db",
              "instance 0 data path");
    expect_true(t1.redirect("/data/data/com.example.sample/databases/m.db", out), "data path matches (1)");
    expect_eq(out, "/data/user/0/com.unique/files/virtual/users/1/data/com.example.sample/databases/m.db",
              "instance 1 data path");

    // Every spelling of external storage.
    for (const char* p : {"/sdcard/DCIM/a.jpg", "/storage/emulated/0/DCIM/a.jpg",
                          "/storage/self/primary/DCIM/a.jpg", "/mnt/sdcard/DCIM/a.jpg"}) {
        expect_true(t0.redirect(p, out), p);
        expect_eq(out, "/data/user/0/com.unique/files/virtual/users/0/sdcard/DCIM/a.jpg", p);
    }

    // The root of a rule redirects to the root of its target.
    expect_true(t0.redirect("/sdcard", out), "bare /sdcard");
    expect_eq(out, "/data/user/0/com.unique/files/virtual/users/0/sdcard", "bare /sdcard target");

    // Unrelated paths are left alone, and the caller can tell.
    expect_false(t0.redirect("/system/lib64/libc.so", out), "/system untouched");
    expect_false(t0.redirect("/proc/self/maps", out), "/proc untouched");
    expect_false(t0.redirect("relative/path", out), "relative untouched");
    expect_false(t0.redirect(nullptr, out), "null tolerated");

    // A prefix must match a whole path component: com.example.sample must not capture
    // com.example.sample2. Getting this wrong would cross-map two different apps' data.
    expect_false(t0.redirect("/data/data/com.example.sample2/files/x", out), "sibling package untouched");
    expect_false(t0.redirect("/sdcard2/x", out), "/sdcard2 untouched");

    // Traversal cannot be used to escape a rule.
    expect_true(t0.redirect("/data/data/com.example.sample/./files/../files/x", out), "traversal normalised");
    expect_eq(out, "/data/user/0/com.unique/files/virtual/users/0/data/com.example.sample/files/x",
              "traversal target");
    expect_true(t0.redirect("//sdcard//DCIM//a.jpg", out), "duplicate separators");
    expect_eq(out, "/data/user/0/com.unique/files/virtual/users/0/sdcard/DCIM/a.jpg", "duplicate separators target");

    // Longest prefix wins regardless of insertion order.
    unique::RedirectTable ordered;
    ordered.set({{"/a", "/short"}, {"/a/b/c", "/long"}, {"/a/b", "/mid"}});
    expect_true(ordered.redirect("/a/b/c/d", out), "longest prefix");
    expect_eq(out, "/long/d", "longest prefix target");
    expect_true(ordered.redirect("/a/b/x", out), "mid prefix");
    expect_eq(out, "/mid/x", "mid prefix target");
    expect_true(ordered.redirect("/a/z", out), "short prefix");
    expect_eq(out, "/short/z", "short prefix target");

    // Normalisation itself.
    expect_eq(unique::RedirectTable::normalize("/a/b/../c/./d//e"), "/a/c/d/e", "normalize");
    expect_eq(unique::RedirectTable::normalize("/../.."), "/", "normalize above root");
    expect_eq(unique::RedirectTable::normalize("/"), "/", "normalize root");

    // An empty table is a no-op, which is the state before :server publishes rules.
    unique::RedirectTable empty;
    expect_false(empty.redirect("/sdcard/x", out), "empty table is a no-op");

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
