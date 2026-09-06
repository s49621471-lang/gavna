#pragma once
// What a guest sees when it reads /proc about itself.
//
// `redirect_table.h` answers the question "where does this path really live". This
// answers the opposite one: "what would this path look like if the app were installed".
// The two are inverses, and both are needed, because a virtual app can reach UNIQUE's
// paths without ever opening one of them — `/proc/self/maps` lists every file the process
// has mapped, and inside a `:vappN` that list is a complete description of the engine:
//
//     7b2c4a1000-7b2f9e3000 r--p ... /data/app/~~eOlB8_.../com.unique-LcSgGP.../base.apk
//     7b30112000-7b3aa8e000 r-xp ... /data/user/0/com.unique/files/virtual/apk/
//                                    com.axlebolt.standoff2/203908/lib/arm64-v8a/libunity.so
//
// An installed app's own maps names its own package and nothing else. Two lines like the
// ones above are all a game needs to know it is not installed, and reading them costs one
// `fopen` and no permission at all. Anti-cheat code lives in the app's own native
// libraries, which is exactly the code the PLT hook covers.
//
// So the view rewrites, and does not hide. Every line is kept, at its real address, with
// its real permissions and its real size — only the *paths* are put into the shape an
// installed app would have. That matters beyond appearances: Unity's crash handler,
// Crashlytics and every native unwinder read this file to turn addresses into symbols,
// and a view with lines removed would break them in ways that look like the app's fault.
//
// Kept in a header with no Android or JNI dependency, like the redirect table, so the
// rewriting is compiled and tested on the build host — see tools/native-test.

#include <cstddef>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

#include "redirect_table.h"

namespace unique {

/// The files this view answers, and the paths inside them.
class ProcView {
public:
    /// @param rules real path prefix -> the prefix an installed app would show.
    void set(std::vector<RedirectRule> rules) {
        std::sort(rules.begin(), rules.end(), [](const RedirectRule& a, const RedirectRule& b) {
            return a.from.size() > b.from.size();
        });
        rules_ = std::move(rules);
    }

    void clear() { rules_.clear(); }

    [[nodiscard]] bool empty() const { return rules_.empty(); }

    [[nodiscard]] size_t size() const { return rules_.size(); }

    /// Whether [path] is a file this view answers, for the process with pid [pid].
    ///
    /// `self` and the process's own pid are the same file and both are used in the wild;
    /// a *different* pid is deliberately not covered, because rewriting another process's
    /// maps would be inventing a device rather than describing this one.
    ///
    /// `smaps` is included because it is `maps` with statistics: same paths, same leak.
    /// `task/<tid>/maps` is the per-thread spelling of the same file.
    [[nodiscard]] static bool covers(const char* path, int pid) {
        if (path == nullptr) return false;
        const char* rest = after_proc_self(path, pid);
        if (rest == nullptr) return false;
        // /proc/<self>/task/<tid>/maps — the thread number is not checked against
        // anything: every task directory belongs to this process by construction.
        if (std::strncmp(rest, "task/", 5) == 0) {
            const char* slash = std::strchr(rest + 5, '/');
            if (slash == nullptr) return false;
            if (!all_digits(rest + 5, slash)) return false;
            rest = slash + 1;
        }
        return std::strcmp(rest, "maps") == 0 || std::strcmp(rest, "smaps") == 0;
    }

    /// Rewrites every path in [text], leaving everything else byte for byte.
    ///
    /// One left-to-right scan, trying the longest rule first at each position. A pass per
    /// rule would be simpler and wrong: the output of one rule can contain the input of
    /// another, and a path rewritten twice is worse than one not rewritten at all.
    [[nodiscard]] std::string rewrite(const std::string& text) const {
        if (rules_.empty()) return text;
        std::string out;
        out.reserve(text.size());
        size_t i = 0;
        while (i < text.size()) {
            const RedirectRule* hit = match_at(text, i);
            if (hit == nullptr) {
                out.push_back(text[i]);
                ++i;
                continue;
            }
            out.append(hit->to);
            i += hit->from.size();
        }
        return out;
    }

    /// Rewrites one whole path — a `readlink` answer, say.
    ///
    /// Returns false and leaves [out] alone when nothing matched, so the caller can hand
    /// back the original buffer untouched.
    [[nodiscard]] bool rewrite_path(const char* path, size_t len, std::string& out) const {
        if (path == nullptr || rules_.empty()) return false;
        const std::string text(path, len);
        std::string rewritten = rewrite(text);
        if (rewritten == text) return false;
        out = std::move(rewritten);
        return true;
    }

private:
    /// The part of [path] after `/proc/self/` or `/proc/<pid>/`, or null.
    static const char* after_proc_self(const char* path, int pid) {
        static constexpr char kProc[] = "/proc/";
        const size_t n = sizeof(kProc) - 1;
        if (std::strncmp(path, kProc, n) != 0) return nullptr;
        const char* p = path + n;
        if (std::strncmp(p, "self/", 5) == 0) return p + 5;
        const char* slash = std::strchr(p, '/');
        if (slash == nullptr || !all_digits(p, slash)) return nullptr;
        if (parse_int(p, slash) != pid) return nullptr;
        return slash + 1;
    }

    static bool all_digits(const char* begin, const char* end) {
        if (begin == end) return false;
        for (const char* p = begin; p != end; ++p) {
            if (*p < '0' || *p > '9') return false;
        }
        return true;
    }

    static int parse_int(const char* begin, const char* end) {
        long value = 0;
        for (const char* p = begin; p != end; ++p) {
            value = value * 10 + (*p - '0');
            if (value > 0x7fffffff) return -1;
        }
        return static_cast<int>(value);
    }

    /// The longest rule whose `from` starts at [i], or null.
    [[nodiscard]] const RedirectRule* match_at(const std::string& text, size_t i) const {
        for (const auto& rule : rules_) {
            const size_t n = rule.from.size();
            if (n == 0 || i + n > text.size()) continue;
            if (text.compare(i, n, rule.from) != 0) continue;
            // Only a whole path component may match, so a rule for
            // /data/user/0/com.unique never captures /data/user/0/com.uniquely.
            const size_t after = i + n;
            if (after < text.size()) {
                const char c = text[after];
                const bool boundary = c == '/' || c == '\n' || c == ' ' || c == '\t' ||
                    c == '\r' || c == '!' || c == ':' || c == '"' || c == '\0';
                if (!boundary) continue;
            }
            return &rule;
        }
        return nullptr;
    }

    std::vector<RedirectRule> rules_;
};

}  // namespace unique
