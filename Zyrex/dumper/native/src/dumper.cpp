#include "dumper.h"
#include "il2cpp_api.h"
#include "guard.h"
#include "log.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <string>
#include <vector>
#include <algorithm>

namespace zyrex {
namespace {

// ---------------------------------------------------------------------------
// Assembly names Unity emits for a standard player build. Used as the fallback
// enumeration path when mono_domain_get_assemblies_iter is unavailable, since
// this build has il2cpp_domain_get_assemblies stripped out.
// ---------------------------------------------------------------------------
const char* kKnownAssemblies[] = {
    "Assembly-CSharp", "Assembly-CSharp-firstpass",
    "mscorlib", "System", "System.Core", "System.Xml", "System.Configuration",
    "Mono.Security", "netstandard",
    "UnityEngine", "UnityEngine.CoreModule", "UnityEngine.PhysicsModule",
    "UnityEngine.AnimationModule", "UnityEngine.InputLegacyModule",
    "UnityEngine.InputModule", "UnityEngine.UI", "UnityEngine.UIModule",
    "UnityEngine.IMGUIModule", "UnityEngine.TextRenderingModule",
    "UnityEngine.AudioModule", "UnityEngine.UnityWebRequestModule",
    "UnityEngine.AIModule", "UnityEngine.ParticleSystemModule",
    "UnityEngine.TerrainModule", "UnityEngine.TerrainPhysicsModule",
    "UnityEngine.VehiclesModule", "UnityEngine.ClothModule",
    "UnityEngine.Physics2DModule", "UnityEngine.UIElementsModule",
    "UnityEngine.TextCoreModule", "UnityEngine.TextCoreTextModule",
    "UnityEngine.AndroidJNIModule", "UnityEngine.JSONSerializeModule",
    "UnityEngine.AssetBundleModule", "UnityEngine.SubsystemsModule",
    "UnityEngine.VideoModule", "UnityEngine.XRModule",
    "UnityEngine.SharedInternalsModule", "UnityEngine.DirectorModule",
    "UnityEngine.GridModule", "UnityEngine.SpriteMaskModule",
    "UnityEngine.StreamingModule", "UnityEngine.UnityAnalyticsModule",
    "UnityEngine.UnityConnectModule", "UnityEngine.ScreenCaptureModule",
    "UnityEngine.AccessibilityModule", "UnityEngine.RuntimeInitializeOnLoadManagerInitializer",
    "Unity.TextMeshPro", "Unity.InputSystem", "Unity.Addressables",
    "Unity.ResourceManager", "Unity.Networking.Transport", "Unity.Burst",
    "Unity.Collections", "Unity.Analytics", "Unity.Services.Core.Internal",
    "Unity.Purchasing", "Unity.Timeline", "Unity.VisualEffectGraph.Runtime",
    "Dissonance", "DissonanceVoip",
};

struct ImageEntry {
    const Il2CppImage* image;
    std::string        name;
};

bool is_framework(const std::string& n) {
    static const char* kPrefixes[] = {
        "mscorlib", "System", "Mono.", "netstandard", "UnityEngine",
        "Unity.", "I18N", "Newtonsoft", "Dissonance", "Google", "Firebase",
        "AppLovin", "Facebook", "Photon", "ExitGames", "DOTween", "Sirenix",
    };
    for (const char* p : kPrefixes) {
        if (n.rfind(p, 0) == 0) return true;
    }
    return false;
}

bool is_unity(const std::string& n) {
    return n.rfind("UnityEngine", 0) == 0 || n.rfind("Unity.", 0) == 0;
}

// ---------------------------------------------------------------------------
// Enumeration
// ---------------------------------------------------------------------------
void collect_images(std::vector<ImageEntry>& out) {
    Il2CppDomain* domain = api.domain_get();
    if (!domain) {
        LOGE("il2cpp_domain_get returned null");
        return;
    }

    auto push = [&out](const Il2CppAssembly* asmb) {
        if (!asmb) return;
        const Il2CppImage* img = api.assembly_get_image(asmb);
        if (!img) return;
        for (const auto& e : out) {
            if (e.image == img) return;      // dedup across both paths
        }
        const char* n = api.image_get_name(img);
        out.push_back({img, n ? n : "<unnamed>"});
    };

    // Path A — mono compatibility iterator. Bounded so a corrupted iterator
    // state cannot spin forever.
    if (api.mono_domain_get_assemblies_iter) {
        void* iter = nullptr;
        for (int i = 0; i < 1024; ++i) {
            Il2CppAssembly* a = api.mono_domain_get_assemblies_iter(domain, &iter);
            if (!a) break;
            push(a);
        }
        LOGI("mono iterator produced %zu images", out.size());
    }

    // Path B — open every assembly name a Unity player build can contain.
    // Cheap, and it covers the case where the iterator is missing or lied.
    size_t before = out.size();
    for (const char* name : kKnownAssemblies) {
        push(api.domain_assembly_open(domain, name));
    }
    LOGI("name fallback added %zu images (total %zu)", out.size() - before, out.size());
}

// ---------------------------------------------------------------------------
// Per-class emission
// ---------------------------------------------------------------------------
struct ClassJob {
    Il2CppClass* klass;
    FILE*        out;
    // filled in by the walker, read by the candidate scorer
    int   n_float      = 0;
    int   n_int        = 0;
    int   n_bool       = 0;
    int   n_string     = 0;
    int   n_transform  = 0;
    int   n_gameobject = 0;
    int   n_vector3    = 0;
    int   n_collection = 0;
    int   n_fields     = 0;
    int   n_methods    = 0;
    int   instance_size = 0;
    bool  is_monobehaviour = false;
    bool  has_static_collection = false;
    std::string full_name;
    std::string parent_chain;
};

std::string class_full_name(Il2CppClass* k) {
    if (!k) return "<null>";
    const char* ns = api.class_get_namespace ? api.class_get_namespace(k) : nullptr;
    const char* nm = api.class_get_name ? api.class_get_name(k) : nullptr;
    std::string s;
    if (ns && ns[0]) { s = ns; s += "."; }
    s += (nm ? nm : "<anon>");
    return s;
}

void emit_class(void* arg) {
    auto* job = static_cast<ClassJob*>(arg);
    Il2CppClass* k = job->klass;
    FILE* f = job->out;

    job->full_name = class_full_name(k);

    // Parent chain — the only reliable way to spot MonoBehaviour subclasses
    // once the game's own type names are obfuscated.
    {
        Il2CppClass* p = api.class_get_parent ? api.class_get_parent(k) : nullptr;
        for (int depth = 0; p && depth < 16; ++depth) {
            std::string pn = class_full_name(p);
            if (!job->parent_chain.empty()) job->parent_chain += " -> ";
            job->parent_chain += pn;
            if (pn == "UnityEngine.MonoBehaviour") job->is_monobehaviour = true;
            p = api.class_get_parent ? api.class_get_parent(p) : nullptr;
        }
    }

    if (api.class_instance_size) job->instance_size = api.class_instance_size(k);

    const uint32_t token = api.class_get_type_token ? api.class_get_type_token(k) : 0;
    fprintf(f, "\n// token 0x%08X  size 0x%X%s\n", token, job->instance_size,
            job->is_monobehaviour ? "  [MonoBehaviour]" : "");
    fprintf(f, "class %s", job->full_name.c_str());
    if (!job->parent_chain.empty()) fprintf(f, " : %s", job->parent_chain.c_str());
    fprintf(f, "\n{\n");

    // ---- fields ----
    {
        void* iter = nullptr;
        char tname[512];
        for (int guardCount = 0; guardCount < 4096; ++guardCount) {
            FieldInfo* fld = api.class_get_fields(k, &iter);
            if (!fld) break;
            job->n_fields++;

            const char* fname = api.field_get_name(fld);
            const Il2CppType* ftype = api.field_get_type ? api.field_get_type(fld) : nullptr;
            type_name(ftype, tname, sizeof(tname));
            size_t off = api.field_get_offset(fld);
            int flags = api.field_get_flags ? api.field_get_flags(fld) : 0;

            const bool is_static = (flags & 0x10) != 0;   // FIELD_ATTRIBUTE_STATIC
            fprintf(f, "    %s%s %s; // 0x%zX\n",
                    is_static ? "static " : "", tname, fname ? fname : "<anon>", off);

            // shape counters for the candidate scorer
            const std::string t(tname);
            if (t == "System.Single")  job->n_float++;
            else if (t == "System.Int32" || t == "System.Byte" ||
                     t == "System.Int16" || t == "System.Int64") job->n_int++;
            else if (t == "System.Boolean") job->n_bool++;
            else if (t == "System.String")  job->n_string++;
            else if (t == "UnityEngine.Transform")  job->n_transform++;
            else if (t == "UnityEngine.GameObject") job->n_gameobject++;
            else if (t == "UnityEngine.Vector3")    job->n_vector3++;

            const bool collectionish =
                t.find("List`1") != std::string::npos ||
                t.find("[]")     != std::string::npos ||
                t.find("Dictionary`2") != std::string::npos;
            if (collectionish) {
                job->n_collection++;
                if (is_static) job->has_static_collection = true;
            }
        }
    }

    // ---- properties ----
    if (api.class_get_properties && api.property_get_name) {
        void* iter = nullptr;
        fprintf(f, "\n");
        for (int i = 0; i < 2048; ++i) {
            PropertyInfo* p = api.class_get_properties(k, &iter);
            if (!p) break;
            const char* pn = api.property_get_name(p);
            const MethodInfo* g = api.property_get_get_method ? api.property_get_get_method(p) : nullptr;
            const MethodInfo* s = api.property_get_set_method ? api.property_get_set_method(p) : nullptr;
            fprintf(f, "    property %s { %s%s} // get RVA 0x%lX  set RVA 0x%lX\n",
                    pn ? pn : "<anon>", g ? "get; " : "", s ? "set; " : "",
                    (unsigned long)method_pointer(g), (unsigned long)method_pointer(s));
        }
    }

    // ---- methods ----
    {
        void* iter = nullptr;
        char rname[512], pname[512];
        fprintf(f, "\n");
        for (int i = 0; i < 8192; ++i) {
            const MethodInfo* m = api.class_get_methods(k, &iter);
            if (!m) break;
            job->n_methods++;

            const char* mn = api.method_get_name(m);
            const Il2CppType* rt = api.method_get_return_type ? api.method_get_return_type(m) : nullptr;
            type_name(rt, rname, sizeof(rname));

            std::string params;
            const uint32_t pc = api.method_get_param_count ? api.method_get_param_count(m) : 0;
            for (uint32_t pi = 0; pi < pc && pi < 32; ++pi) {
                const Il2CppType* pt = api.method_get_param ? api.method_get_param(m, pi) : nullptr;
                type_name(pt, pname, sizeof(pname));
                const char* pnm = api.method_get_param_name ? api.method_get_param_name(m, pi) : nullptr;
                if (pi) params += ", ";
                params += pname;
                if (pnm && pnm[0]) { params += " "; params += pnm; }
            }

            fprintf(f, "    %s %s(%s); // RVA 0x%lX\n",
                    rname, mn ? mn : "<anon>", params.c_str(),
                    (unsigned long)method_pointer(m));
        }
    }

    fprintf(f, "}\n");
}

// ---------------------------------------------------------------------------
// Player-candidate scoring
//
// The game's own type names are obfuscated, so the player class has to be found
// by shape instead. An FPS player script is a MonoBehaviour that owns a
// Transform, carries a float (health), an int (team/kills), a bool (dead/local)
// and usually a string (nickname). Static collection fields are scored
// separately because that is where an entity list normally lives.
// ---------------------------------------------------------------------------
struct Candidate {
    std::string name;
    std::string parent;
    int  score = 0;
    int  size  = 0;
    int  fields = 0, methods = 0;
    int  f_float = 0, f_int = 0, f_bool = 0, f_string = 0, f_transform = 0, f_vec3 = 0;
    bool static_collection = false;
};

int score_of(const ClassJob& j) {
    if (!j.is_monobehaviour) return 0;
    int s = 0;
    if (j.n_transform)  s += 25;
    if (j.n_gameobject) s += 10;
    if (j.n_float >= 1) s += 20;
    if (j.n_float >= 3) s += 10;
    if (j.n_int   >= 1) s += 10;
    if (j.n_bool  >= 2) s += 10;
    if (j.n_string)     s += 15;
    if (j.n_vector3)    s += 15;
    if (j.n_collection) s += 5;
    if (j.instance_size >= 0x60 && j.instance_size <= 0x600) s += 10;
    if (j.n_methods >= 10) s += 5;
    return s;
}

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------
FILE* open_out(const char* dir, const char* name) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE* f = fopen(path, "w");
    if (!f) LOGE("cannot open %s (errno %d)", path, errno);
    else    LOGI("writing %s", path);
    return f;
}

} // namespace

void run_dump(const char* out_dir) {
    if (!out_dir || !out_dir[0]) {
        LOGE("no output directory");
        return;
    }

    std::vector<ImageEntry> images;
    collect_images(images);
    if (images.empty()) {
        LOGE("no assemblies resolved — nothing to dump");
        return;
    }

    FILE* f_game    = open_out(out_dir, "02_game.cs");
    FILE* f_unity   = open_out(out_dir, "03_unity.cs");
    FILE* f_other   = open_out(out_dir, "04_other.cs");
    if (!f_game || !f_unity || !f_other) {
        if (f_game)  fclose(f_game);
        if (f_unity) fclose(f_unity);
        if (f_other) fclose(f_other);
        return;
    }

    std::vector<Candidate> candidates;
    std::vector<std::string> static_lists;

    size_t total_classes = 0, skipped = 0;

    for (const auto& img : images) {
        size_t count = 0;
        if (api.image_get_class_count) count = api.image_get_class_count(img.image);
        if (count == 0 || count > 200000) {
            LOGW("image %s reports %zu classes — skipping", img.name.c_str(), count);
            continue;
        }

        FILE* target = is_unity(img.name) ? f_unity
                     : (is_framework(img.name) ? f_other : f_game);

        fprintf(target, "\n// ===========================================================\n");
        fprintf(target, "// assembly: %s   (%zu classes)\n", img.name.c_str(), count);
        fprintf(target, "// ===========================================================\n");

        for (size_t i = 0; i < count; ++i) {
            auto* k = const_cast<Il2CppClass*>(api.image_get_class(img.image, i));
            if (!k) { skipped++; continue; }

            ClassJob job;
            job.klass = k;
            job.out   = target;

            // Every class walk is fenced: a class whose metadata the protector
            // mangled takes itself out of the dump instead of the process.
            if (!guard_run(emit_class, &job)) {
                skipped++;
                fprintf(target, "\n// [!] fault while dumping class index %zu of %s — skipped\n",
                        i, img.name.c_str());
                continue;
            }

            total_classes++;

            if (target == f_game) {
                int s = score_of(job);
                if (s >= 45) {
                    Candidate c;
                    c.name = job.full_name;
                    c.parent = job.parent_chain;
                    c.score = s;
                    c.size = job.instance_size;
                    c.fields = job.n_fields;
                    c.methods = job.n_methods;
                    c.f_float = job.n_float;
                    c.f_int = job.n_int;
                    c.f_bool = job.n_bool;
                    c.f_string = job.n_string;
                    c.f_transform = job.n_transform;
                    c.f_vec3 = job.n_vector3;
                    c.static_collection = job.has_static_collection;
                    candidates.push_back(std::move(c));
                }
                if (job.has_static_collection) static_lists.push_back(job.full_name);
            }
        }
    }

    fclose(f_game);
    fclose(f_unity);
    fclose(f_other);

    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& a, const Candidate& b) { return a.score > b.score; });

    // ---- candidates ----
    if (FILE* f = open_out(out_dir, "01_candidates.txt")) {
        fprintf(f, "Zyrex — player class candidates\n");
        fprintf(f, "Ranked by structural shape, because the game's type names are obfuscated.\n");
        fprintf(f, "Columns: score, instance size, field counts.\n\n");
        int shown = 0;
        for (const auto& c : candidates) {
            if (shown++ >= 120) break;
            fprintf(f, "[%3d] %s\n", c.score, c.name.c_str());
            fprintf(f, "      parent   : %s\n", c.parent.c_str());
            fprintf(f, "      size     : 0x%X   fields %d   methods %d\n", c.size, c.fields, c.methods);
            fprintf(f, "      shape    : float=%d int=%d bool=%d string=%d Transform=%d Vector3=%d%s\n\n",
                    c.f_float, c.f_int, c.f_bool, c.f_string, c.f_transform, c.f_vec3,
                    c.static_collection ? "  [has static collection]" : "");
        }
        fprintf(f, "\n\n=== classes holding a static collection field (entity-list candidates) ===\n");
        for (const auto& s : static_lists) fprintf(f, "  %s\n", s.c_str());
        fclose(f);
    }

    // ---- summary ----
    if (FILE* f = open_out(out_dir, "00_summary.txt")) {
        fprintf(f, "Zyrex dump summary\n");
        fprintf(f, "==================\n\n");
        fprintf(f, "libil2cpp.so base : %p\n", (void*)il2cpp_base);
        fprintf(f, "libil2cpp.so size : 0x%zx\n", il2cpp_size);
        fprintf(f, "assemblies        : %zu\n", images.size());
        fprintf(f, "classes dumped    : %zu\n", total_classes);
        fprintf(f, "classes skipped   : %zu\n", skipped);
        fprintf(f, "faults absorbed   : %d\n", guard_fault_count());
        fprintf(f, "player candidates : %zu\n", candidates.size());
        fprintf(f, "\nenumeration path  : %s\n",
                api.mono_domain_get_assemblies_iter ? "mono iterator + name fallback"
                                                    : "name fallback only");
        fprintf(f, "\nassembly list:\n");
        for (const auto& e : images) fprintf(f, "  %s\n", e.name.c_str());
        fclose(f);
    }

    LOGI("dump complete: %zu classes, %zu skipped, %d faults",
         total_classes, skipped, guard_fault_count());
}

} // namespace zyrex
