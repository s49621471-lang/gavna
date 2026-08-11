#include "probe.h"
#include "il2cpp_api.h"
#include "guard.h"
#include "log.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <ctime>
#include <set>
#include <string>
#include <vector>

namespace zyrex {
namespace {

// Il2CppArray: object header (0x10), bounds (0x10), max_length (0x18),
// element storage begins at 0x20. Stable across every IL2CPP revision.
constexpr size_t kArrayData = 0x20;

// Hard ceiling on players read per team, so a corrupted length cannot walk off
// into unmapped memory even before the fault guard would catch it.
constexpr uint32_t kMaxSlots = 64;

enum class Kind {
    F32, I8, U8, I16, U16, I32, U32, I64, U64, Boolean, Str, Vec2, Vec3, Quat, Ref, Skip
};

struct Field {
    std::string name;
    std::string type;
    size_t      offset = 0;
    Kind        kind   = Kind::Skip;

    // Range across the session. Health is the float that starts near 100 and
    // drops; a constant field is not what we are looking for.
    double min_v = 0, max_v = 0;
    bool   seen  = false;
    int    changes = 0;
    double last_v = 0;
    char   last_str[128] = {0};

    // Distinct values seen across *players*. The nickname field is the one that
    // reads differently for every slot; a shared constant is not it.
    std::set<std::string> distinct;
};

Kind kind_of(const std::string& t) {
    if (t == "System.Single" || t == "System.Double") return Kind::F32;
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
    // Object references are logged as null/non-null only: the address itself is
    // meaningless across launches, but "does this slot have a model attached"
    // separates live players from empty slots.
    if (t.rfind("System.", 0) != 0) return Kind::Ref;
    return Kind::Skip;
}

void sleep_ms(int ms) {
    struct timespec ts { ms / 1000, (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, nullptr);
}

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

void track(Field& f, double v) {
    if (!f.seen) { f.min_v = f.max_v = f.last_v = v; f.seen = true; return; }
    if (v < f.min_v) f.min_v = v;
    if (v > f.max_v) f.max_v = v;
    if (v != f.last_v) { f.changes++; f.last_v = v; }
}

void note_distinct(Field& f, const char* s) {
    if (f.distinct.size() < 64) f.distinct.insert(s);
}

// ---------------------------------------------------------------------------
// Field table for a class, resolved once
// ---------------------------------------------------------------------------
struct FieldSet {
    std::vector<Field> fields;
    FieldInfo*         self_static = nullptr;   // static field of the class's own type
};

FieldSet resolve_fields(Il2CppClass* klass, const char* self_type) {
    FieldSet fs;
    void* iter = nullptr;
    char tname[512];
    for (int i = 0; i < 4096; ++i) {
        FieldInfo* fi = api.class_get_fields(klass, &iter);
        if (!fi) break;

        const char* fname = api.field_get_name(fi);
        const Il2CppType* ft = api.field_get_type ? api.field_get_type(fi) : nullptr;
        type_name(ft, tname, sizeof(tname));
        const int flags = api.field_get_flags ? api.field_get_flags(fi) : 0;

        if ((flags & 0x10) != 0) {                       // FIELD_ATTRIBUTE_STATIC
            if (self_type && !fs.self_static && strcmp(tname, self_type) == 0) {
                fs.self_static = fi;
                LOGI("probe: static self-ref on %s is %s", self_type, fname ? fname : "?");
            }
            continue;
        }

        Field f;
        f.name   = fname ? fname : "?";
        f.type   = tname;
        f.offset = api.field_get_offset(fi);
        f.kind   = kind_of(f.type);
        if (f.kind != Kind::Skip) fs.fields.push_back(std::move(f));
    }
    return fs;
}

// ---------------------------------------------------------------------------
// One object -> one CSV row tail
// ---------------------------------------------------------------------------
struct RowJob {
    void*               obj    = nullptr;
    std::vector<Field>* fields = nullptr;
    FILE*               csv    = nullptr;
    bool                ok     = false;
};

void write_row(void* arg) {
    auto* j = static_cast<RowJob*>(arg);
    char* base = static_cast<char*>(j->obj);
    char buf[256];

    for (auto& f : *j->fields) {
        char* p = base + f.offset;
        switch (f.kind) {
            case Kind::F32: { float v = *reinterpret_cast<float*>(p);
                if (std::isfinite(v)) { track(f, v); snprintf(buf,sizeof buf,"%.4f",v); note_distinct(f,buf); }
                fprintf(j->csv, ",%.4f", v); break; }
            case Kind::I8:  { int8_t   v=*reinterpret_cast<int8_t*>(p);   track(f,v); snprintf(buf,sizeof buf,"%d",v);  note_distinct(f,buf); fprintf(j->csv,",%d",v); break; }
            case Kind::U8:  { uint8_t  v=*reinterpret_cast<uint8_t*>(p);  track(f,v); snprintf(buf,sizeof buf,"%u",v);  note_distinct(f,buf); fprintf(j->csv,",%u",v); break; }
            case Kind::I16: { int16_t  v=*reinterpret_cast<int16_t*>(p);  track(f,v); snprintf(buf,sizeof buf,"%d",v);  note_distinct(f,buf); fprintf(j->csv,",%d",v); break; }
            case Kind::U16: { uint16_t v=*reinterpret_cast<uint16_t*>(p); track(f,v); snprintf(buf,sizeof buf,"%u",v);  note_distinct(f,buf); fprintf(j->csv,",%u",v); break; }
            case Kind::I32: { int32_t  v=*reinterpret_cast<int32_t*>(p);  track(f,v); snprintf(buf,sizeof buf,"%d",v);  note_distinct(f,buf); fprintf(j->csv,",%d",v); break; }
            case Kind::U32: { uint32_t v=*reinterpret_cast<uint32_t*>(p); track(f,v); snprintf(buf,sizeof buf,"%u",v);  note_distinct(f,buf); fprintf(j->csv,",%u",v); break; }
            case Kind::I64: { int64_t  v=*reinterpret_cast<int64_t*>(p);  track(f,(double)v); fprintf(j->csv,",%lld",(long long)v); break; }
            case Kind::U64: { uint64_t v=*reinterpret_cast<uint64_t*>(p); track(f,(double)v); fprintf(j->csv,",%llu",(unsigned long long)v); break; }
            case Kind::Boolean: { uint8_t v=*reinterpret_cast<uint8_t*>(p); track(f,v?1:0); snprintf(buf,sizeof buf,"%d",v?1:0); note_distinct(f,buf); fprintf(j->csv,",%d",v?1:0); break; }
            case Kind::Str: {
                void* so = *reinterpret_cast<void**>(p);
                read_string(so, buf, sizeof buf);
                if (buf[0]) { snprintf(f.last_str, sizeof f.last_str, "%s", buf); f.seen = true; note_distinct(f, buf); }
                fprintf(j->csv, ",\"%s\"", buf);
                break;
            }
            case Kind::Ref: {
                void* r = *reinterpret_cast<void**>(p);
                fprintf(j->csv, ",%d", r ? 1 : 0);
                break;
            }
            case Kind::Vec2: { float* v=reinterpret_cast<float*>(p); fprintf(j->csv,",%.3f|%.3f",v[0],v[1]); break; }
            case Kind::Vec3: { float* v=reinterpret_cast<float*>(p);
                snprintf(buf,sizeof buf,"%.1f|%.1f|%.1f",v[0],v[1],v[2]); note_distinct(f,buf);
                fprintf(j->csv,",%.3f|%.3f|%.3f",v[0],v[1],v[2]); break; }
            case Kind::Quat: { float* v=reinterpret_cast<float*>(p); fprintf(j->csv,",%.3f|%.3f|%.3f|%.3f",v[0],v[1],v[2],v[3]); break; }
            default: break;
        }
    }
    j->ok = true;
}

void check_alive(void* arg) {
    auto* j = static_cast<RowJob*>(arg);
    j->ok = api.object_get_class(j->obj) != nullptr;
}

bool alive(void* obj) {
    if (!obj) return false;
    if (!api.object_get_class) return true;
    RowJob j; j.obj = obj;
    return guard_run(check_alive, &j) && j.ok;
}

void write_header(FILE* f, const char* lead, const std::vector<Field>& fields) {
    fprintf(f, "%s", lead);
    for (const auto& fl : fields) {
        fprintf(f, ",%s@0x%zX:%s", fl.name.c_str(), fl.offset, fl.type.c_str());
    }
    fprintf(f, "\n");
}

void write_summary(const char* path, const char* title, const std::vector<Field>& fields,
                   int taken, int missed, const char* guide) {
    FILE* f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "%s\n", title);
    fprintf(f, "samples %d, skipped %d\n\n", taken, missed);
    fprintf(f, "%-16s %-9s %-24s %11s %11s %8s %9s\n",
            "FIELD", "OFFSET", "TYPE", "MIN", "MAX", "CHANGES", "DISTINCT");
    fprintf(f, "%s\n", "--------------------------------------------------------"
                       "--------------------------------------------------");
    for (const auto& fl : fields) {
        if (fl.kind == Kind::Str || fl.kind == Kind::Ref || fl.kind == Kind::Vec3) continue;
        if (!fl.seen) continue;
        char off[16]; snprintf(off, sizeof off, "0x%zX", fl.offset);
        fprintf(f, "%-16s %-9s %-24s %11.3f %11.3f %8d %9zu\n",
                fl.name.c_str(), off, fl.type.c_str(), fl.min_v, fl.max_v,
                fl.changes, fl.distinct.size());
    }
    fprintf(f, "\n=== string fields ===\n");
    for (const auto& fl : fields) {
        if (fl.kind != Kind::Str) continue;
        fprintf(f, "  %-16s 0x%-7zX distinct=%zu  last=%s\n", fl.name.c_str(), fl.offset,
                fl.distinct.size(), fl.last_str[0] ? fl.last_str : "(empty)");
        int shown = 0;
        for (const auto& s : fl.distinct) {
            if (shown++ >= 12) { fprintf(f, "      ...\n"); break; }
            fprintf(f, "      \"%s\"\n", s.c_str());
        }
    }
    fprintf(f, "\n=== Vector3 fields (distinct positions seen) ===\n");
    for (const auto& fl : fields) {
        if (fl.kind != Kind::Vec3) continue;
        fprintf(f, "  %-16s 0x%-7zX distinct=%zu\n", fl.name.c_str(), fl.offset, fl.distinct.size());
    }
    fprintf(f, "\n%s\n", guide);
    fclose(f);
}

const Il2CppImage* game_image() {
    Il2CppDomain* d = api.domain_get();
    if (!d) return nullptr;
    const Il2CppAssembly* a = api.domain_assembly_open(d, "Assembly-CSharp");
    return a ? api.assembly_get_image(a) : nullptr;
}

// ---------------------------------------------------------------------------
// Probe A — the local player's weapon controller (Shooter)
// ---------------------------------------------------------------------------
void probe_local(const Il2CppImage* image, const char* out_dir, int samples, int interval_ms) {
    Il2CppClass* shooter = api.class_from_name(image, "", "Shooter");
    if (!shooter) { LOGE("probe: Shooter not found"); return; }

    FieldSet fs = resolve_fields(shooter, "Shooter");
    if (!fs.self_static || fs.fields.empty()) {
        LOGE("probe: Shooter unusable (self_static=%p fields=%zu)",
             (void*)fs.self_static, fs.fields.size());
        return;
    }

    char path[1024];
    snprintf(path, sizeof path, "%s/05_probe.csv", out_dir);
    FILE* csv = fopen(path, "w");
    if (!csv) return;
    write_header(csv, "t", fs.fields);

    int taken = 0, missed = 0;
    double t = 0;
    for (int i = 0; i < samples; ++i, t += interval_ms / 1000.0) {
        void* inst = nullptr;
        api.field_static_get_value(fs.self_static, &inst);
        if (!alive(inst)) { missed++; sleep_ms(interval_ms); continue; }

        fprintf(csv, "%.2f", t);
        RowJob j; j.obj = inst; j.fields = &fs.fields; j.csv = csv;
        if (guard_run(write_row, &j) && j.ok) taken++; else missed++;
        fprintf(csv, "\n");

        if ((i % 40) == 0) fflush(csv);
        sleep_ms(interval_ms);
    }
    fclose(csv);
    LOGI("probe(local): %d taken, %d skipped", taken, missed);

    snprintf(path, sizeof path, "%s/06_probe_ranges.txt", out_dir);
    write_summary(path, "Zyrex — local Shooter (weapon controller)", fs.fields, taken, missed,
                  "Shooter drives the weapon: recoil, spread, shot index, ADS state.");
}

// ---------------------------------------------------------------------------
// Probe B — every player in both team arrays (PLH)
//
// PLH holds two static NNLLADJLMMB[] arrays. Which array a record sits in *is*
// its team, so no team field has to be identified. Reading both gives cross-
// player variation in a single match, which is what separates a nickname from a
// shared constant and health from a config value.
// ---------------------------------------------------------------------------
void probe_players(const Il2CppImage* image, const char* out_dir, int samples, int interval_ms) {
    Il2CppClass* plh = api.class_from_name(image, "", "PLH");
    if (!plh) { LOGE("probe: PLH not found"); return; }

    Il2CppClass* rec = api.class_from_name(image, "", "NNLLADJLMMB");
    if (!rec) { LOGE("probe: NNLLADJLMMB not found"); return; }

    // The two team arrays, found by type rather than by obfuscated name.
    std::vector<FieldInfo*> arrays;
    {
        void* iter = nullptr;
        char tname[512];
        for (int i = 0; i < 4096; ++i) {
            FieldInfo* fi = api.class_get_fields(plh, &iter);
            if (!fi) break;
            const int flags = api.field_get_flags ? api.field_get_flags(fi) : 0;
            if ((flags & 0x10) == 0) continue;
            const Il2CppType* ft = api.field_get_type ? api.field_get_type(fi) : nullptr;
            type_name(ft, tname, sizeof tname);
            if (strcmp(tname, "NNLLADJLMMB[]") == 0) {
                arrays.push_back(fi);
                const char* n = api.field_get_name(fi);
                LOGI("probe: team array %zu is %s", arrays.size(), n ? n : "?");
            }
        }
    }
    if (arrays.empty()) { LOGE("probe: no NNLLADJLMMB[] statics on PLH"); return; }

    FieldSet fs = resolve_fields(rec, nullptr);
    if (fs.fields.empty()) { LOGE("probe: no sampleable fields on player record"); return; }
    LOGI("probe: %zu player fields, %zu team arrays", fs.fields.size(), arrays.size());

    char path[1024];
    snprintf(path, sizeof path, "%s/07_players.csv", out_dir);
    FILE* csv = fopen(path, "w");
    if (!csv) return;
    write_header(csv, "t,team,slot", fs.fields);

    int taken = 0, missed = 0;
    double t = 0;
    for (int i = 0; i < samples; ++i, t += interval_ms / 1000.0) {
        bool any = false;
        for (size_t team = 0; team < arrays.size(); ++team) {
            void* arr = nullptr;
            api.field_static_get_value(arrays[team], &arr);
            if (!alive(arr)) continue;

            uint32_t len = api.array_length ? api.array_length(arr)
                                            : *reinterpret_cast<uint32_t*>(static_cast<char*>(arr) + 0x18);
            if (len == 0 || len > kMaxSlots) continue;

            for (uint32_t s = 0; s < len; ++s) {
                void* obj = *reinterpret_cast<void**>(static_cast<char*>(arr) + kArrayData + s * 8);
                if (!alive(obj)) continue;

                fprintf(csv, "%.2f,%zu,%u", t, team, s);
                RowJob j; j.obj = obj; j.fields = &fs.fields; j.csv = csv;
                if (guard_run(write_row, &j) && j.ok) { taken++; any = true; }
                fprintf(csv, "\n");
            }
        }
        if (!any) missed++;
        if ((i % 20) == 0) fflush(csv);
        sleep_ms(interval_ms);
    }
    fclose(csv);
    LOGI("probe(players): %d rows, %d empty samples", taken, missed);

    snprintf(path, sizeof path, "%s/08_players_summary.txt", out_dir);
    write_summary(path, "Zyrex — player records from PLH team arrays", fs.fields, taken, missed,
        "Reading this:\n"
        "  nickname  - the string with one distinct value per player\n"
        "  health    - float near 100 that drops; armor sits beside it\n"
        "  position  - Vector3 with many distinct values spread over the map\n"
        "  alive     - bool that flips on death and back on respawn\n"
        "  team      - not a field: which array the record sits in IS the team");
}

} // namespace

void run_probe(const char* out_dir, int duration_sec, int interval_ms) {
    if (!out_dir || !out_dir[0]) return;

    const Il2CppImage* image = game_image();
    if (!image || !api.class_from_name || !api.field_static_get_value) {
        LOGE("probe: prerequisites unavailable");
        return;
    }

    // Both probes share the window: half the samples each, interleaved by
    // running players first since that is the one that matters now.
    const int samples = (duration_sec * 1000) / interval_ms;
    probe_players(image, out_dir, samples, interval_ms);
    probe_local(image, out_dir, samples / 4, interval_ms);
}

} // namespace zyrex
