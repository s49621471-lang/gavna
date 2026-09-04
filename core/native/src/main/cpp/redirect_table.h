#pragma once
// Path redirection table.
//
// Kept in a header with no Android or JNI dependency so the same code can be compiled
// and unit-tested on the build host (see tools/native-test). The rewriting rules are the
// hottest code in the engine — every open(), stat() and access() a virtual app performs
// goes through redirect() — so it is a sorted array scan with no allocation on the
// matching path, not a regex engine or a hash map.

#include <cstddef>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

namespace unique {

struct RedirectRule {
    std::string from;
    std::string to;
};

class RedirectTable {
public:
    // Rules are stored longest-prefix-first. Order is significant: a rule for
    // /data/data/<pkg>/cache must be tested before one for /data/data/<pkg>, or the
    // cache directory silently lands inside the data directory's mapping.
    void set(std::vector<RedirectRule> rules) {
        std::sort(rules.begin(), rules.end(), [](const RedirectRule& a, const RedirectRule& b) {
            return a.from.size() > b.from.size();
        });
        rules_ = std::move(rules);
    }

    void clear() { rules_.clear(); }

    [[nodiscard]] bool empty() const { return rules_.empty(); }

    [[nodiscard]] size_t size() const { return rules_.size(); }

    // Returns true and fills `out` when `path` was rewritten; returns false and leaves
    // `out` untouched otherwise. Returning false rather than copying the input is what
    // keeps the common case (a path we do not care about) allocation-free.
    [[nodiscard]] bool redirect(const char* path, std::string& out) const {
        if (path == nullptr || path[0] != '/' || rules_.empty()) return false;

        // Normalising every path would dominate the cost, so it is done only when the
        // path actually contains a traversal component — which is rare in practice but
        // is exactly how a rule would otherwise be bypassed.
        if (needs_normalize(path)) {
            std::string norm = normalize(path);
            return match(norm.c_str(), norm.size(), out);
        }
        return match(path, std::strlen(path), out);
    }

    // Exposed for testing: collapses ".", ".." and duplicate separators.
    static std::string normalize(const char* path) {
        std::vector<std::string> parts;
        const bool absolute = path[0] == '/';
        const char* p = path;
        while (*p) {
            while (*p == '/') ++p;
            const char* start = p;
            while (*p && *p != '/') ++p;
            if (p == start) break;
            std::string seg(start, static_cast<size_t>(p - start));
            if (seg == ".") continue;
            if (seg == "..") {
                if (!parts.empty() && parts.back() != "..") parts.pop_back();
                else if (!absolute) parts.emplace_back("..");
                continue;
            }
            parts.push_back(std::move(seg));
        }
        std::string out;
        if (absolute) out.push_back('/');
        for (size_t i = 0; i < parts.size(); ++i) {
            if (i) out.push_back('/');
            out += parts[i];
        }
        if (out.empty()) out = absolute ? "/" : ".";
        return out;
    }

private:
    static bool needs_normalize(const char* path) {
        for (const char* p = path; *p; ++p) {
            if (*p == '/' && (p[1] == '/' || p[1] == '\0')) return true;
            if (*p == '.' && (p == path || p[-1] == '/')) {
                if (p[1] == '/' || p[1] == '\0') return true;
                if (p[1] == '.' && (p[2] == '/' || p[2] == '\0')) return true;
            }
        }
        return false;
    }

    [[nodiscard]] bool match(const char* path, size_t len, std::string& out) const {
        for (const auto& rule : rules_) {
            const size_t n = rule.from.size();
            if (len < n) continue;
            if (std::memcmp(path, rule.from.data(), n) != 0) continue;
            // Only a whole path component may match, so /data/data/com.foo never
            // captures /data/data/com.foobar.
            if (len != n && path[n] != '/') continue;
            out.assign(rule.to);
            if (len != n) out.append(path + n, len - n);
            return true;
        }
        return false;
    }

    std::vector<RedirectRule> rules_;
};

}  // namespace unique
