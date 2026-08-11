#include "probe.h"
#include "il2cpp_api.h"
#include "guard.h"
#include "log.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <ctime>
#include <string>
#include <vector>

namespace zyrex {
namespace {

// ---------------------------------------------------------------------------
// Field description, resolved once up front so the sample loop stays cheap.
// ---------------------------------------------------------------------------
enum class Kind {
    F32, I8, U8, I16, U16, I32, U32, I64, U64, Boolean, Str, Vec2, Vec3, Quat, Skip
};

struct Field {
    std::string name;
    std::string type;
    size_t      offset = 0;
    Kind        kind   = Kind::Skip;

    // running range across the session — this is what actually identifies a
    // field. Health is the float that starts at 100 and drops.
    double min_v = 0, max_v = 0;
    bool   seen  = false;
    int    changes = 0;
    double last_v = 0;
    char   last_str[128] = {0};   // most recent value for System.String fields
};

Kind kind_of(const std::string& t) {
    if (t == "System.Single")  return Kind::F32;
    if (t == "System.Double")  return Kind::F32;   // printed as double anyway
    if (t == "System.Int32")   return Kind::I32;
    if (t == "System.UInt32")  return Kind::U32;
    if (t == "System.Boolean") return Kind::Boolean;
    if (t == "System.Byte")    return Kind::U8;
    if (t == "System.SByte")   return Kind::I8;
    if (t == "System.Int16")   return Kind::I16;
    if (t == "System.UInt16")  return Kind::U16;
    if (t == "System.Int64")   return Kind::I64;
    if (t == "System.UInt64")  return Kind::U64;
    if (t == "System.String")  return Kind::Str;
    if (t == "UnityEngine.Vector3")    return Kind::Vec3;
    if (t == "UnityEngine.Vector2")    return Kind::Vec2;
    if (t == "UnityEngine.Quaternion") return Kind::Quat;
    // Object references are just addresses that change every launch — they say
    // nothing about which field means what, so they are left out.
    return Kind::Skip;
}

void sleep_ms(int ms) {
    struct timespec ts { ms / 1000, (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, nullptr);
}

// Reads an Il2CppString into an ASCII buffer. Layout is stable: object header,
// int32 length at 0x10, UTF-16 payload at 0x14.
void read_string(void* strobj, char* out, size_t out_len) {
    out[0] = '\0';
    if (!strobj || out_len < 2) return;
    const int32_t len = *reinterpret_cast<int32_t*>(static_cast<char*>(strobj) + 0x10);
    if (len <= 0 || len > 512) return;
    const uint16_t* chars = api.string_chars
        ? api.string_chars(strobj)
        : reinterpret_cast<uint16_t*>(static_cast<char*>(strobj) + 0x14);
    if (!chars) return;
    size_t n = 0;
    for (int i = 0; i < len && n + 1 < out_len; ++i) {
        const uint16_t c = chars[i];
        out[n++] = (c >= 0x20 && c < 0x7f) ? (char)c : '?';
    }
    out[n] = '\0';
}

// ---------------------------------------------------------------------------
// Sample state, shared with the guarded callback
// ---------------------------------------------------------------------------
struct Sample {
    void*               instance = nullptr;
    std::vector<Field>* fields   = nullptr;
    FILE*               csv      = nullptr;
    double              t        = 0;
    bool                ok       = false;
};

void check_alive(void* arg) {
    auto* s = static_cast<Sample*>(arg);
    s->ok = api.object_get_class(s->instance) != nullptr;
}

void track(Field& f, double v) {
    if (!f.seen) { f.min_v = f.max_v = f.last_v = v; f.seen = true; return; }
    if (v < f.min_v) f.min_v = v;
    if (v > f.max_v) f.max_v = v;
    if (v != f.last_v) { f.changes++; f.last_v = v; }
}

void take_sample(void* arg) {
    auto* s = static_cast<Sample*>(arg);
    char* base = static_cast<char*>(s->instance);
    char strbuf[256];

    fprintf(s->csv, "%.2f", s->t);
    for (auto& f : *s->fields) {
        char* p = base + f.offset;
        switch (f.kind) {
            case Kind::F32: {
                float v = *reinterpret_cast<float*>(p);
                if (std::isfinite(v)) track(f, v);
                fprintf(s->csv, ",%.4f", v);
                break;
            }
            case Kind::I8:  { int8_t   v = *reinterpret_cast<int8_t*>(p);   track(f, v); fprintf(s->csv, ",%d", v); break; }
            case Kind::U8:  { uint8_t  v = *reinterpret_cast<uint8_t*>(p);  track(f, v); fprintf(s->csv, ",%u", v); break; }
            case Kind::I16: { int16_t  v = *reinterpret_cast<int16_t*>(p);  track(f, v); fprintf(s->csv, ",%d", v); break; }
            case Kind::U16: { uint16_t v = *reinterpret_cast<uint16_t*>(p); track(f, v); fprintf(s->csv, ",%u", v); break; }
            case Kind::I32: { int32_t  v = *reinterpret_cast<int32_t*>(p);  track(f, v); fprintf(s->csv, ",%d", v); break; }
            case Kind::U32: { uint32_t v = *reinterpret_cast<uint32_t*>(p); track(f, v); fprintf(s->csv, ",%u", v); break; }
            case Kind::I64: { int64_t  v = *reinterpret_cast<int64_t*>(p);  track(f, (double)v); fprintf(s->csv, ",%lld", (long long)v); break; }
            case Kind::U64: { uint64_t v = *reinterpret_cast<uint64_t*>(p); track(f, (double)v); fprintf(s->csv, ",%llu", (unsigned long long)v); break; }
            case Kind::Boolean: { uint8_t v = *reinterpret_cast<uint8_t*>(p); track(f, v ? 1 : 0); fprintf(s->csv, ",%d", v ? 1 : 0); break; }
            case Kind::Str: {
                void* so = *reinterpret_cast<void**>(p);
                read_string(so, strbuf, sizeof(strbuf));
                if (strbuf[0]) {
                    snprintf(f.last_str, sizeof(f.last_str), "%s", strbuf);
                    f.seen = true;
                }
                fprintf(s->csv, ",\"%s\"", strbuf);
                break;
            }
            case Kind::Vec2: {
                float* v = reinterpret_cast<float*>(p);
                fprintf(s->csv, ",%.3f|%.3f", v[0], v[1]);
                break;
            }
            case Kind::Vec3: {
                float* v = reinterpret_cast<float*>(p);
                fprintf(s->csv, ",%.3f|%.3f|%.3f", v[0], v[1], v[2]);
                break;
            }
            case Kind::Quat: {
                float* v = reinterpret_cast<float*>(p);
                fprintf(s->csv, ",%.3f|%.3f|%.3f|%.3f", v[0], v[1], v[2], v[3]);
                break;
            }
            default: break;
        }
    }
    fprintf(s->csv, "\n");
    s->ok = true;
}

// ---------------------------------------------------------------------------

const Il2CppImage* game_image() {
    Il2CppDomain* d = api.domain_get();
    if (!d) return nullptr;
    const Il2CppAssembly* a = api.domain_assembly_open(d, "Assembly-CSharp");
    return a ? api.assembly_get_image(a) : nullptr;
}

} // namespace

void run_probe(const char* out_dir, int duration_sec, int interval_ms) {
    if (!out_dir || !out_dir[0]) return;

    const Il2CppImage* image = game_image();
    if (!image) {
        LOGE("probe: Assembly-CSharp image unavailable");
        return;
    }
    if (!api.class_from_name) {
        LOGE("probe: il2cpp_class_from_name unavailable");
        return;
    }

    Il2CppClass* shooter = api.class_from_name(image, "", "Shooter");
    if (!shooter) {
        LOGE("probe: class Shooter not found");
        return;
    }
    LOGI("probe: Shooter resolved at %p", (void*)shooter);

    // ---- instance field table -------------------------------------------
    std::vector<Field> fields;
    // ---- the static self-reference is the local player -------------------
    FieldInfo* local_field = nullptr;
    {
        void* iter = nullptr;
        char tname[512];
        for (int i = 0; i < 4096; ++i) {
            FieldInfo* fi = api.class_get_fields(shooter, &iter);
            if (!fi) break;

            const char* fname = api.field_get_name(fi);
            const Il2CppType* ft = api.field_get_type ? api.field_get_type(fi) : nullptr;
            type_name(ft, tname, sizeof(tname));
            const int flags = api.field_get_flags ? api.field_get_flags(fi) : 0;
            const bool is_static = (flags & 0x10) != 0;

            if (is_static) {
                // Found by type rather than by name: the obfuscated identifier
                // can change between game versions, the self-reference cannot.
                if (!local_field && strcmp(tname, "Shooter") == 0) {
                    local_field = fi;
                    LOGI("probe: local-player static field is %s", fname ? fname : "?");
                }
                continue;
            }

            Field f;
            f.name   = fname ? fname : "?";
            f.type   = tname;
            f.offset = api.field_get_offset(fi);
            f.kind   = kind_of(f.type);
            if (f.kind != Kind::Skip) fields.push_back(std::move(f));
        }
    }
    LOGI("probe: %zu sampleable instance fields", fields.size());

    if (!local_field) {
        LOGE("probe: no static Shooter self-reference — cannot locate local player");
        return;
    }
    if (fields.empty()) {
        LOGE("probe: no sampleable fields");
        return;
    }
    if (!api.field_static_get_value) {
        LOGE("probe: il2cpp_field_static_get_value unavailable");
        return;
    }

    // ---- csv ------------------------------------------------------------
    char path[1024];
    snprintf(path, sizeof(path), "%s/05_probe.csv", out_dir);
    FILE* csv = fopen(path, "w");
    if (!csv) {
        LOGE("probe: cannot open %s", path);
        return;
    }
    fprintf(csv, "t");
    for (const auto& f : fields) {
        fprintf(csv, ",%s@0x%zX:%s", f.name.c_str(), f.offset, f.type.c_str());
    }
    fprintf(csv, "\n");

    // ---- sample loop ----------------------------------------------------
    const int    total   = (duration_sec * 1000) / interval_ms;
    int          taken   = 0, missed = 0;
    double       t       = 0;

    for (int i = 0; i < total; ++i, t += interval_ms / 1000.0) {
        void* instance = nullptr;
        api.field_static_get_value(local_field, &instance);

        if (!instance) {
            missed++;                       // menus, respawn, between rounds
            sleep_ms(interval_ms);
            continue;
        }

        // Cheap liveness check: a real managed object starts with its class
        // pointer. Anything else and we skip rather than read 344 fields off it.
        if (api.object_get_class) {
            Sample live{};
            live.instance = instance;
            if (!guard_run(check_alive, &live) || !live.ok) {
                missed++;
                sleep_ms(interval_ms);
                continue;
            }
        }

        Sample s;
        s.instance = instance;
        s.fields   = &fields;
        s.csv      = csv;
        s.t        = t;
        if (guard_run(take_sample, &s) && s.ok) taken++;
        else missed++;

        if ((i % 40) == 0) fflush(csv);
        sleep_ms(interval_ms);
    }
    fclose(csv);
    LOGI("probe: %d samples taken, %d skipped", taken, missed);

    // ---- ranges ---------------------------------------------------------
    snprintf(path, sizeof(path), "%s/06_probe_ranges.txt", out_dir);
    if (FILE* f = fopen(path, "w")) {
        fprintf(f, "Zyrex field probe — per-field range across the session\n");
        fprintf(f, "samples taken %d, skipped %d (skipped = no local player: menu, dead, loading)\n\n",
                taken, missed);
        fprintf(f, "%-16s %-10s %-24s %12s %12s %8s\n",
                "FIELD", "OFFSET", "TYPE", "MIN", "MAX", "CHANGES");
        fprintf(f, "%s\n", "----------------------------------------------------"
                           "------------------------------------------");
        for (const auto& fl : fields) {
            if (!fl.seen || fl.kind == Kind::Str) continue;
            char off[16];
            snprintf(off, sizeof(off), "0x%zX", fl.offset);
            fprintf(f, "%-16s %-10s %-24s %12.3f %12.3f %8d\n",
                    fl.name.c_str(), off, fl.type.c_str(), fl.min_v, fl.max_v, fl.changes);
        }

        fprintf(f, "\n\n=== string fields (last observed value) ===\n");
        for (const auto& fl : fields) {
            if (fl.kind != Kind::Str) continue;
            fprintf(f, "  %-16s 0x%-8zX %s\n", fl.name.c_str(), fl.offset,
                    fl.last_str[0] ? fl.last_str : "(never populated)");
        }

        fprintf(f, "\n\nReading this:\n");
        fprintf(f, "  health  — starts near 100, drops when hit, jumps back on respawn\n");
        fprintf(f, "  armor   — same shape, usually a second float alongside health\n");
        fprintf(f, "  team    — small int, constant within a match\n");
        fprintf(f, "  ammo    — int that steps down as you fire and resets on reload\n");
        fprintf(f, "  strings — the one holding your username is the nickname field\n");
        fclose(f);
    }
}

} // namespace zyrex
