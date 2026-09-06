// Host-side test that the two path tables are inverses of each other.
//
// UNIQUE now runs both directions at once, and they meet inside a single call. A guest
// asking for its own canonical path goes:
//
//     File.getCanonicalPath()                       /data/user/0/com.axlebolt.standoff2/files
//       → realpath(argument)   redirect table  →    …/virtual/users/1/data/…/files
//       → the kernel resolves it
//       → realpath's answer    proc view       →    /data/user/0/com.axlebolt.standoff2/files
//
// If the two tables disagree by one character, the guest is handed a path that does not
// come back to where it started — which is either a directory it cannot read or, worse,
// UNIQUE's own. Both tables are built from `VirtualPathModel` and are asserted to agree
// on the Kotlin side; this asserts that the two *implementations* agree, which is a
// different claim and the one that runs on the phone.
//
// Build and run with tools/native-test/run.sh.

#include "../../core/native/src/main/cpp/proc_view.h"
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

/// The redirect table's answer, or the path unchanged when no rule matched.
static std::string inward_of(const unique::RedirectTable& table, const std::string& path) {
    std::string out;
    return table.redirect(path.c_str(), out) ? out : path;
}

// The real layout, from the thirteenth phone log: instance 1 of Standoff 2 0.39.3 under
// UNIQUE's own files directory on a single-user device.
static const char* kHostFiles = "/data/user/0/com.unique/files/virtual";
static const char* kRealApk =
    "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908";
static const char* kRealLib =
    "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/lib/arm64-v8a";
static const char* kRealData =
    "/data/user/0/com.unique/files/virtual/users/1/data/com.axlebolt.standoff2";
static const char* kRealExt = "/data/user/0/com.unique/files/virtual/users/1/sdcard";

// What the guest is told, which is what an installed copy of it would report.
static const char* kPublicApk =
    "/data/app/~~kQ2wJmH8vRs4TdLpXbYnAg/com.axlebolt.standoff2-Zx9FbN2mQpR7LsVtEc4WdA";
static const char* kPublicData = "/data/user/0/com.axlebolt.standoff2";

int main() {
    // Inward: what a guest hands out, resolved to what is really on disk. The order is
    // the one `VirtualPathModel.redirectionRules` produces — longest prefix first, which
    // the table enforces itself.
    unique::RedirectTable inward;
    inward.set({
        {std::string(kPublicApk) + "/lib/arm64", kRealLib},
        {kPublicApk, kRealApk},
        {kPublicData, kRealData},
        {"/data/data/com.axlebolt.standoff2", kRealData},
        {"/storage/emulated/0", kRealExt},
        {"/sdcard", kRealExt},
    });

    // Outward: what the kernel says, rewritten into what the guest is told.
    unique::ProcView outward;
    outward.set({
        {kRealLib, std::string(kPublicApk) + "/lib/arm64"},
        {kRealApk, kPublicApk},
        {kRealData, kPublicData},
        {kRealExt, "/storage/emulated/0"},
        {kHostFiles, kPublicData},
    });

    // ---- the round trip a canonical path makes -----------------------------------
    const std::vector<std::string> asked = {
        std::string(kPublicData) + "/files/save.dat",
        std::string(kPublicData) + "/databases/game.db",
        std::string(kPublicApk) + "/base.apk",
        std::string(kPublicApk) + "/lib/arm64/libunity.so",
        "/storage/emulated/0/Android/obb/com.axlebolt.standoff2/main.203908.obb",
    };
    for (const std::string& path : asked) {
        const std::string real = inward_of(inward, path);
        expect_true(real != path, ("a guest path is redirected inward: " + path).c_str());
        std::string shown;
        expect_true(outward.rewrite_path(real.c_str(), real.size(), shown),
                    ("the real path is rewritten outward: " + real).c_str());
        expect_eq(shown, path, ("the round trip lands where it started: " + path).c_str());
    }

    // ---- what must NOT round-trip ------------------------------------------------
    // UNIQUE's own files, which is the whole safety argument for hooking the platform's
    // IO libraries: no inward rule may match them, in any process, ever.
    for (const char* mine : {
             "/data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/base.apk",
             "/data/user/0/com.unique/shared_prefs/unique.xml",
             "/data/user/0/com.unique/databases/unique.db",
             "/data/user/0/com.unique/files/diagnostics/unique.log",
         }) {
        expect_eq(inward_of(inward, mine), mine, "UNIQUE's own path is not redirected");
    }

    // The host's own APK, which every process in the phone has mapped.
    expect_eq(inward_of(inward, "/data/app/~~aB/com.unique-cD/base.apk"),
              "/data/app/~~aB/com.unique-cD/base.apk",
              "the host's installed APK is not redirected");

    // ---- the outward table does hide UNIQUE, which is the other half -------------
    // The length is passed explicitly because that is how `readlink` answers: no
    // terminator, and the caller is not entitled to a byte past what was returned.
    std::string shown;
    const std::string mapped = std::string(kRealLib) + "/libunity.so";
    expect_true(outward.rewrite_path(mapped.c_str(), mapped.size(), shown),
                "a mapped guest library is renamed");
    expect_eq(shown, std::string(kPublicApk) + "/lib/arm64/libunity.so",
              "and is renamed to the installed spelling");

    // A path under UNIQUE's directory that no specific rule covers still must not come
    // back naming UNIQUE: the catch-all on the host's files root is what makes that true,
    // and it is the rule most easily lost in a refactor.
    const std::string uncovered = std::string(kHostFiles) + "/anything";
    expect_true(outward.rewrite_path(uncovered.c_str(), uncovered.size(), shown),
                "an uncovered path under UNIQUE's root is still rewritten");
    expect_true(shown.find("com.unique") == std::string::npos,
                "and what comes back does not name UNIQUE");

    // And a length shorter than the string is honoured rather than read past: the buffer
    // `readlink` filled is the only thing that is safe to look at.
    const std::string truncated = mapped.substr(0, std::string(kRealApk).size());
    expect_true(outward.rewrite_path(mapped.c_str(), truncated.size(), shown),
                "a truncated answer is still rewritten");
    expect_eq(shown, kPublicApk, "and only the bytes that were there are used");

    // ---- the library directory is spelled differently on each side ---------------
    // `lib/arm64-v8a` inside the APK, `lib/arm64` after installation. An app that knows
    // the difference is exactly the kind that is looking, so the two tables must each use
    // the right one and they are asserted here rather than assumed.
    expect_eq(inward_of(inward, std::string(kPublicApk) + "/lib/arm64/libmain.so"),
              std::string(kRealLib) + "/libmain.so",
              "the installed spelling resolves to the extracted one");

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
