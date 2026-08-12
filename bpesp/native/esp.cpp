// BLOCKPOST ESP — native side.
//
// One attached poller thread walks the il2cpp heap, resolves the player
// container, projects every entity to screen space and publishes a snapshot.
// The Java overlay only reads the snapshot, so nothing managed is ever touched
// from the UI thread.
//
// Everything of consequence is written to bpesp.log next to the game's external
// files directory, because logcat is unreliable on MIUI and a discovery failure
// needs to be diagnosable from the log alone.

#include "il2cpp.h"
#include "offsets.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cmath>

#define MAX_ENT 64
#define JOINTS  12
#define STRIDE  (14 + JOINTS * 2)
#define NO_HOP  ((size_t)-1)

// ---------------------------------------------------------------------------
// logging
// ---------------------------------------------------------------------------
static FILE           *g_logf;
static pthread_mutex_t g_logLock = PTHREAD_MUTEX_INITIALIZER;
static char            g_logPath[512];

static double now_s() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void bplog(const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    __android_log_print(ANDROID_LOG_INFO, "bpesp", "%s", msg);

    pthread_mutex_lock(&g_logLock);
    if (g_logf) {
        fprintf(g_logf, "[%8.2f] %s\n", now_s(), msg);
        fflush(g_logf);
    }
    pthread_mutex_unlock(&g_logLock);
}

static void log_open() {
    if (g_logf || g_logPath[0] == '\0') return;
    pthread_mutex_lock(&g_logLock);
    if (!g_logf) g_logf = fopen(g_logPath, "w");
    pthread_mutex_unlock(&g_logLock);
    if (g_logf) bplog("log opened: %s", g_logPath);
    else __android_log_print(ANDROID_LOG_WARN, "bpesp", "cannot open %s", g_logPath);
}

// ---------------------------------------------------------------------------
// snapshot
// ---------------------------------------------------------------------------
struct EntityView {
    float data[STRIDE];
    char  name[40];
};

static EntityView      g_ent[MAX_ENT];
static int             g_count = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int   g_state = 0;      // 0 wait il2cpp, 1 scanning, 2 live, 3 no container
static char  g_status[192] = "starting";
static float g_screenW = 0, g_screenH = 0;

/** Replaces the snapshot. Publishing zero is how boxes vanish: every path that
 *  cannot produce a frame must call this, or the overlay keeps painting the
 *  last good frame forever — which is what kept dead players on screen. */
static void publish(const struct EntityView *src, int n);

static void set_status(int st, const char *fmt, ...) {
    char tmp[192];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);

    pthread_mutex_lock(&g_lock);
    bool changed = (g_state != st) || strcmp(g_status, tmp) != 0;
    g_state = st;
    snprintf(g_status, sizeof(g_status), "%s", tmp);
    pthread_mutex_unlock(&g_lock);

    if (changed) bplog("state=%d %s", st, tmp);   // this is polled, so log edges only
}

// ---------------------------------------------------------------------------
// config, pushed from the menu
// ---------------------------------------------------------------------------
struct Config {
    bool  teamCheck = true;
    bool  aimbot    = false;
    float fov       = 90.0f;    // degrees, full cone; 360 means no limit
    float speed     = 25.0f;    // 1..100, percent of the remaining angle per tick
    int   bone      = 0;        // 0 head, 1 chest, 2 hip, 3 nearest
    bool  rcs       = false;
    float rcsPower  = 60.0f;    // 0..100
};
static Config          g_cfg;
static pthread_mutex_t g_cfgLock = PTHREAD_MUTEX_INITIALIZER;

static Config cfg() {
    pthread_mutex_lock(&g_cfgLock);
    Config c = g_cfg;
    pthread_mutex_unlock(&g_cfgLock);
    return c;
}

// ---------------------------------------------------------------------------
// resolved game types
// ---------------------------------------------------------------------------
struct Resolved {
    void *image     = nullptr;
    void *playerCls = nullptr;
    char  playerName[64] = {0};
    bool  offsetsExact = false;

    void *objectCls = nullptr;      // UnityEngine.Object, for the scene sweep

    size_t oSlot = OFF_SLOT,      oName  = OFF_NAME,        oHp    = OFF_HEALTH;
    size_t oArm  = OFF_ARMOR,     oKill  = OFF_KILLS,       oDeath = OFF_DEATHS;
    size_t oPos  = OFF_POSITION,  oDir   = OFF_LOOK_DIR,    oMove  = OFF_MOVE;
    size_t oScore= OFF_SCORE,     oDist  = OFF_DIST_LIFE,   oNoTf  = OFF_NO_TRANSFORM;

    void *mGetMain = nullptr, *mView = nullptr, *mProj = nullptr;
    void *mPixW = nullptr, *mPixH = nullptr;
    void *camObj = nullptr;
};
static Resolved R;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
static void *unbox(void *boxed) { return boxed ? (uint8_t *)boxed + 0x10 : nullptr; }

static void *invoke(void *method, void *obj, void **params) {
    if (!method) return nullptr;
    void *exc = nullptr;
    void *r = g_il2.runtime_invoke(method, obj, params, &exc);
    return exc ? nullptr : r;
}

static const char *cls_name(void *cls) {
    const char *n = cls ? g_il2.class_get_name(cls) : nullptr;
    return n ? n : "?";
}

/** Field composition of a class, used both to match and to explain a mismatch. */
struct Traits {
    int strings = 0, ints = 0, vec3 = 0, floats = 0, bools = 0, refs = 0;
    int offsetHits = 0;    // fields sitting exactly where the 1.00f3 dump had them
    int nameHits = 0;      // obfuscated names from the dump that survived
    int size = 0;
};

static Traits inspect(void *cls) {
    Traits t;
    t.size = g_il2.class_instance_size(cls);

    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
        if (g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC) continue;
        size_t off = g_il2.field_get_offset(f);
        char *tn = g_il2.type_get_name(g_il2.field_get_type(f));
        if (!tn) continue;

        bool isStr = !strcmp(tn, "System.String");
        bool isInt = !strcmp(tn, "System.Int32");
        bool isVec = !strcmp(tn, "UnityEngine.Vector3");

        if (isStr) t.strings++;
        else if (isInt) t.ints++;
        else if (isVec) t.vec3++;
        else if (!strcmp(tn, "System.Single")) t.floats++;
        else if (!strcmp(tn, "System.Boolean")) t.bools++;
        else t.refs++;

        if (isStr && (off == OFF_NAME || off == OFF_DISPLAY_NAME)) t.offsetHits++;
        if (isInt && (off == OFF_HEALTH || off == OFF_ARMOR || off == OFF_KILLS ||
                      off == OFF_DEATHS || off == OFF_MONEY || off == OFF_SCORE)) t.offsetHits++;
        if (isVec && (off == OFF_POSITION || off == OFF_LOOK_DIR ||
                      off == OFF_NETPOS_FROM || off == OFF_NETPOS_TO)) t.offsetHits++;

        const char *fn = g_il2.field_get_name(f);
        if (fn && (!strcmp(fn, NAME_POSITION) || !strcmp(fn, NAME_HEALTH) ||
                   !strcmp(fn, NAME_NAME) || !strcmp(fn, NAME_MOVE) ||
                   !strcmp(fn, NAME_LOOK_DIR) || !strcmp(fn, NAME_SCORE))) t.nameHits++;

        g_il2.free(tn);
    }
    return t;
}

/** Writes every instance field of a class to the log. This is what makes a
 *  layout change fixable without another dump. */
static void dump_fields(void *cls, int cap) {
    bplog("  --- fields of %s (size 0x%x) ---", cls_name(cls), g_il2.class_instance_size(cls));
    int n = 0;
    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr && n < cap) {
        char *tn = g_il2.type_get_name(g_il2.field_get_type(f));
        int flags = g_il2.field_get_flags(f);
        bplog("  %s%-14s @0x%-4zx %s",
              (flags & FIELD_ATTRIBUTE_STATIC) ? "static " : "       ",
              g_il2.field_get_name(f), g_il2.field_get_offset(f), tn ? tn : "?");
        if (tn) g_il2.free(tn);
        n++;
    }
    bplog("  --- %d fields ---", n);
}

// Field lookup by the obfuscated name from the dump.
static bool resolve_field(void *cls, const char *name, size_t *out) {
    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
        const char *n = g_il2.field_get_name(f);
        if (n && !strcmp(n, name)) { *out = g_il2.field_get_offset(f); return true; }
    }
    return false;
}

// ---------------------------------------------------------------------------
// image discovery
// ---------------------------------------------------------------------------
static void *g_images[8];
static int   g_imageCount = 0;

static void add_image(void *img) {
    if (!img) return;
    for (int i = 0; i < g_imageCount; i++) if (g_images[i] == img) return;
    if (g_imageCount >= 8) return;
    g_images[g_imageCount++] = img;
    const char *n = g_il2.image_get_name(img);
    bplog("image[%d] = %s (%zu classes)", g_imageCount - 1, n ? n : "?",
          g_il2.image_get_class_count(img));
}

static void *unity_image() {
    void *domain = g_il2.domain_get();
    static const char *mods[] = { "UnityEngine.CoreModule", "UnityEngine", nullptr };
    for (int i = 0; mods[i]; i++) {
        void *a = g_il2.domain_assembly_open(domain, mods[i]);
        if (a) { void *img = g_il2.assembly_get_image(a); if (img) return img; }
    }
    return nullptr;
}

/** The game assembly may not be called Assembly-CSharp once an obfuscator has
 *  been through it, and there is no exported way to enumerate assemblies. So
 *  ask Unity for every live MonoBehaviour and take the images they came from. */
static void discover_images_via_scene() {
    void *ue = unity_image();
    if (!ue) { bplog("scene scan: no UnityEngine image"); return; }

    void *objCls = g_il2.class_from_name(ue, "UnityEngine", "Object");
    void *mbCls  = g_il2.class_from_name(ue, "UnityEngine", "MonoBehaviour");
    if (!objCls || !mbCls) { bplog("scene scan: Object/MonoBehaviour not found"); return; }

    void *typeObj = g_il2.type_get_object(g_il2.class_get_type(mbCls));
    if (!typeObj) { bplog("scene scan: no Type object"); return; }

    void *params[2] = { typeObj, nullptr };
    void *arr = invoke(g_il2.class_get_method_from_name(objCls, "FindObjectsOfType", 1),
                       nullptr, params);
    if (!arr) {           // Unity 2020+ also has the (Type, bool) overload
        bool inc = true;
        params[1] = &inc;
        arr = invoke(g_il2.class_get_method_from_name(objCls, "FindObjectsOfType", 2),
                     nullptr, params);
    }
    if (!arr) { bplog("scene scan: FindObjectsOfType returned nothing"); return; }

    size_t n = rd<uint64_t>(arr, 0x18);
    if (n > 8192) n = 8192;
    void **data = (void **)IL2CPP_ARRAY_DATA(arr);
    bplog("scene scan: %zu MonoBehaviours live", n);

    for (size_t i = 0; i < n; i++) {
        if (!data[i]) continue;
        void *k = rd<void *>(data[i], 0);
        if (!k) continue;
        add_image((void *)g_il2.class_get_image(k));
    }
}

static void collect_images() {
    void *domain = g_il2.domain_get();
    static const char *named[] = { "Assembly-CSharp", "Assembly-CSharp-firstpass",
                                   "Main", "Game", nullptr };
    for (int i = 0; named[i]; i++) {
        void *a = g_il2.domain_assembly_open(domain, named[i]);
        if (a) add_image(g_il2.assembly_get_image(a));
        else bplog("assembly '%s' not present", named[i]);
    }
    discover_images_via_scene();
    bplog("%d image(s) to scan", g_imageCount);
}

// ---------------------------------------------------------------------------
// entity class discovery
// ---------------------------------------------------------------------------
static bool plausible_entity(const Traits &t) {
    return t.size >= 0x80 && t.strings >= 2 && t.ints >= 5 && t.vec3 >= 2;
}

static bool find_player_class() {
    void *bestCls = nullptr;
    Traits bestT;
    int bestScore = -1;
    int candidates = 0;

    for (int im = 0; im < g_imageCount; im++) {
        void *img = g_images[im];
        size_t n = g_il2.image_get_class_count(img);
        if (n == 0) { bplog("image %d reports 0 classes", im); continue; }

        for (size_t i = 0; i < n; i++) {
            void *cls = (void *)g_il2.image_get_class(img, i);
            if (!cls) continue;
            if (g_il2.class_instance_size(cls) < 0x80) continue;

            Traits t = inspect(cls);
            if (!plausible_entity(t)) continue;

            candidates++;
            int score = t.nameHits * 10 + t.offsetHits;
            if (score > bestScore) { bestScore = score; bestCls = cls; bestT = t; }

            if (candidates <= 6)
                bplog("candidate %-24s size=0x%-4x str=%d int=%d vec3=%d flt=%d "
                      "offsetHits=%d nameHits=%d",
                      cls_name(cls), t.size, t.strings, t.ints, t.vec3, t.floats,
                      t.offsetHits, t.nameHits);
        }
    }

    bplog("entity scan: %d plausible class(es), best score %d", candidates, bestScore);
    if (!bestCls || bestScore < 3) {
        if (bestCls) { bplog("best candidate was too weak, dumping it anyway:"); dump_fields(bestCls, 120); }
        return false;
    }

    R.playerCls = bestCls;
    R.image = (void *)g_il2.class_get_image(bestCls);
    snprintf(R.playerName, sizeof(R.playerName), "%s", cls_name(bestCls));

    // Offsets: prefer the dumped names, fall back to the 1.00f3 constants.
    int byName = 0;
    byName += resolve_field(bestCls, NAME_SLOT,         &R.oSlot);
    byName += resolve_field(bestCls, NAME_NAME,         &R.oName);
    byName += resolve_field(bestCls, NAME_HEALTH,       &R.oHp);
    byName += resolve_field(bestCls, NAME_ARMOR,        &R.oArm);
    byName += resolve_field(bestCls, NAME_KILLS,        &R.oKill);
    byName += resolve_field(bestCls, NAME_DEATHS,       &R.oDeath);
    byName += resolve_field(bestCls, NAME_POSITION,     &R.oPos);
    byName += resolve_field(bestCls, NAME_LOOK_DIR,     &R.oDir);
    byName += resolve_field(bestCls, NAME_MOVE,         &R.oMove);
    byName += resolve_field(bestCls, NAME_SCORE,        &R.oScore);
    byName += resolve_field(bestCls, NAME_DIST_LIFE,    &R.oDist);
    byName += resolve_field(bestCls, NAME_NO_TRANSFORM, &R.oNoTf);
    R.offsetsExact = (byName >= 8);

    bplog("entity class = %s  size=0x%x  offsets resolved by name: %d/12 (%s)",
          R.playerName, bestT.size, byName,
          R.offsetsExact ? "trusted" : "FALLING BACK TO 1.00f3 CONSTANTS");
    bplog("  hp@0x%zx armor@0x%zx pos@0x%zx dir@0x%zx move@0x%zx name@0x%zx",
          R.oHp, R.oArm, R.oPos, R.oDir, R.oMove, R.oName);
    dump_fields(bestCls, 120);
    return true;
}

// ---------------------------------------------------------------------------
// container discovery
// ---------------------------------------------------------------------------
// 1 = T[]   2 = List<T>   3 = a bare T reference
static int container_kind(void *type) {
    char *tn = g_il2.type_get_name(type);
    if (!tn) return 0;

    int kind = 0;
    size_t len = strlen(tn), pn = strlen(R.playerName);
    if (len > 2 && !strcmp(tn + len - 2, "[]")) {
        if (len - 2 >= pn && !strncmp(tn + len - 2 - pn, R.playerName, pn)) kind = 1;
    } else if (strstr(tn, "List`1<") || strstr(tn, "Dictionary`2<")) {
        char want[80];
        snprintf(want, sizeof(want), "<%s>", R.playerName);
        char want2[80];
        snprintf(want2, sizeof(want2), ",%s>", R.playerName);
        if (strstr(tn, want) || strstr(tn, want2)) kind = 2;
    } else if (!strcmp(tn, R.playerName)) {
        kind = 3;
    }
    g_il2.free(tn);
    return kind;
}

// A place an entity container might live. Several will match on type and only
// one of them is ever populated, so every candidate is kept and the populated
// one is chosen by counting live elements each frame.
#define MAX_CAND 48

struct Cand {
    void  *base     = nullptr;   // static field block, or null when cls is set
    void  *cls      = nullptr;   // scene-resident holder, instances looked up live
    size_t rootOff  = 0;
    size_t chainOff = NO_HOP;
    int    kind     = 0;         // 1 = T[], 2 = List<T>, 3 = bare T
    int    lastCount = 0;
    bool   nullRoot = false;
    char   desc[112] = {0};
};

static Cand g_cand[MAX_CAND];
static int  g_candCount = 0;
static int  g_bestCand  = -1;

static void add_cand(const Cand &c) {
    if (g_candCount >= MAX_CAND) return;
    for (int i = 0; i < g_candCount; i++)
        if (g_cand[i].base == c.base && g_cand[i].cls == c.cls &&
            g_cand[i].rootOff == c.rootOff && g_cand[i].chainOff == c.chainOff) return;
    g_cand[g_candCount++] = c;
    bplog("  candidate container: %s", c.desc);
}

/** UnityEngine.Object.FindObjectsOfType(typeof(cls)) */
static void *find_objects(void *cls) {
    if (!R.objectCls || !cls) return nullptr;
    void *typeObj = g_il2.type_get_object(g_il2.class_get_type(cls));
    if (!typeObj) return nullptr;

    void *params[2] = { typeObj, nullptr };
    void *arr = invoke(g_il2.class_get_method_from_name(R.objectCls, "FindObjectsOfType", 1),
                       nullptr, params);
    if (!arr) {
        bool inc = true;
        params[1] = &inc;
        arr = invoke(g_il2.class_get_method_from_name(R.objectCls, "FindObjectsOfType", 2),
                     nullptr, params);
    }
    return arr;
}

/**
 * Every managed object carries its class pointer at offset 0, so this is an
 * exact identity check rather than a guess. Container discovery follows fields
 * that merely have the right *declared* type — several of them turned out to
 * hold something else entirely, and reading those produced entities with
 * nine-digit health and a blank name.
 */
static bool valid_entity(void *e) {
    if (!e) return false;
    void *k = rd<void *>(e, 0);
    for (int depth = 0; k && depth < 8; depth++) {
        if (k == R.playerCls) break;
        k = g_il2.class_get_parent(k);
    }
    if (k != R.playerCls) return false;

    int hp = rd<int32_t>(e, R.oHp);
    return hp >= 0 && hp <= 1000;
}

/** Resolves one candidate to its element array and copies out non-null entries. */
static int eval_cand(Cand &c, void *base, void **out, int cap) {
    if (!base) return 0;
    void *root = rd<void *>(base, c.rootOff);
    if (!root) { c.nullRoot = true; return 0; }
    c.nullRoot = false;

    if (c.kind == 3) {                      // a bare reference is one entity
        if (cap < 1 || !valid_entity(root)) return 0;
        out[0] = root;
        return 1;
    }
    if (c.chainOff != NO_HOP) {
        root = rd<void *>(root, c.chainOff);
        if (!root) return 0;
    }

    void *arr = root;
    int count;
    if (c.kind == 2) {
        arr   = rd<void *>(root, LIST_ITEMS_OFF);
        count = rd<int32_t>(root, LIST_SIZE_OFF);
        if (!arr) return 0;
    } else {
        count = (int)rd<uint64_t>(root, 0x18);
    }
    if (count < 0 || count > 512) return 0;

    int backing = (int)rd<uint64_t>(arr, 0x18);
    if (count > backing) count = backing;
    if (count > cap) count = cap;

    void **data = (void **)IL2CPP_ARRAY_DATA(arr);
    int got = 0;
    for (int i = 0; i < count; i++) if (valid_entity(data[i])) out[got++] = data[i];
    return got;
}

/**
 * For a scene-resident holder. A bare reference means one component per player,
 * so those accumulate across instances; a collection means one instance owns the
 * whole roster, so the fullest instance wins.
 */
static int eval_scene_cand(Cand &c, void **out, int cap) {
    void *arr = find_objects(c.cls);
    if (!arr) return 0;
    size_t n = rd<uint64_t>(arr, 0x18);
    if (n > 256) n = 256;
    void **objs = (void **)IL2CPP_ARRAY_DATA(arr);

    int best = 0, total = 0;
    void *tmp[MAX_ENT];
    for (size_t i = 0; i < n; i++) {
        if (!objs[i]) continue;
        int got = eval_cand(c, objs[i], tmp, cap);
        if (c.kind == 3) {
            for (int j = 0; j < got && total < cap; j++) {
                bool dup = false;
                for (int k = 0; k < total; k++) if (out[k] == tmp[j]) { dup = true; break; }
                if (!dup) out[total++] = tmp[j];
            }
        } else if (got > best) {
            best = got;
            memcpy(out, tmp, sizeof(void *) * got);
        }
    }
    return c.kind == 3 ? total : best;
}

static int eval_any(Cand &c, void **out, int cap) {
    return c.cls ? eval_scene_cand(c, out, cap) : eval_cand(c, c.base, out, cap);
}

// --- collection passes ------------------------------------------------------
static void collect_static_containers() {
    for (int im = 0; im < g_imageCount; im++) {
        size_t n = g_il2.image_get_class_count(g_images[im]);
        for (size_t i = 0; i < n; i++) {
            void *cls = (void *)g_il2.image_get_class(g_images[im], i);
            if (!cls) continue;
            void *sd = g_il2.class_get_static_field_data(cls);
            if (!sd) continue;

            void *iter = nullptr, *f;
            while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
                if (!(g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC)) continue;
                size_t sOff = g_il2.field_get_offset(f);

                int k = container_kind(g_il2.field_get_type(f));
                if (k) {
                    Cand c;
                    c.base = sd; c.rootOff = sOff; c.chainOff = NO_HOP; c.kind = k;
                    snprintf(c.desc, sizeof(c.desc), "static %s.%s kind=%d",
                             cls_name(cls), g_il2.field_get_name(f), k);
                    add_cand(c);
                    continue;
                }

                // static Manager instance; instance.list
                void *fc = g_il2.class_from_type(g_il2.field_get_type(f));
                if (!fc || g_il2.class_is_valuetype(fc)) continue;
                if (!rd<void *>(sd, sOff)) continue;

                void *it2 = nullptr, *f2;
                while ((f2 = g_il2.class_get_fields(fc, &it2)) != nullptr) {
                    if (g_il2.field_get_flags(f2) & FIELD_ATTRIBUTE_STATIC) continue;
                    int k2 = container_kind(g_il2.field_get_type(f2));
                    if (!k2) continue;
                    Cand c;
                    c.base = sd; c.rootOff = sOff;
                    c.chainOff = g_il2.field_get_offset(f2); c.kind = k2;
                    snprintf(c.desc, sizeof(c.desc), "static %s.%s -> .%s kind=%d",
                             cls_name(cls), g_il2.field_get_name(f),
                             g_il2.field_get_name(f2), k2);
                    add_cand(c);
                }
            }
        }
    }
}

/** The list is very often an instance field on a scene component, reachable
 *  from no static at all. Sweep live objects and remember the holder classes. */
static void collect_scene_containers() {
    void *ue = unity_image();
    void *mb = ue ? g_il2.class_from_name(ue, "UnityEngine", "MonoBehaviour") : nullptr;
    if (!mb) return;

    void *arr = find_objects(mb);
    if (!arr) { bplog("scene sweep: FindObjectsOfType(MonoBehaviour) returned nothing"); return; }
    size_t n = rd<uint64_t>(arr, 0x18);
    if (n > 8192) n = 8192;
    void **objs = (void **)IL2CPP_ARRAY_DATA(arr);
    bplog("scene sweep: %zu live MonoBehaviours", n);

    void *seen[256];
    int seenN = 0;

    for (size_t i = 0; i < n; i++) {
        if (!objs[i]) continue;
        void *cls = rd<void *>(objs[i], 0);
        if (!cls) continue;

        bool dup = false;
        for (int s = 0; s < seenN; s++) if (seen[s] == cls) { dup = true; break; }
        if (dup) continue;
        if (seenN < 256) seen[seenN++] = cls;

        void *iter = nullptr, *f;
        while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
            if (g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC) continue;
            int k = container_kind(g_il2.field_get_type(f));
            if (!k) continue;
            Cand c;
            c.cls = cls; c.rootOff = g_il2.field_get_offset(f);
            c.chainOff = NO_HOP; c.kind = k;
            snprintf(c.desc, sizeof(c.desc), "scene %s.%s kind=%d",
                     cls_name(cls), g_il2.field_get_name(f), k);
            add_cand(c);
        }
    }
}

/** Merges every candidate. The roster is split across containers — one array
 *  per team — so taking only the fullest one silently drops the other side. */
static int read_entities(void **out, int *outCand, int cap, bool verbose) {
    int total = 0, live = 0;
    void *tmp[MAX_ENT];

    for (int i = 0; i < g_candCount; i++) {
        int got = eval_any(g_cand[i], tmp, MAX_ENT);
        g_cand[i].lastCount = got;
        if (got) live++;

        for (int j = 0; j < got && total < cap; j++) {
            bool dup = false;
            for (int k = 0; k < total; k++) if (out[k] == tmp[j]) { dup = true; break; }
            if (!dup) { outCand[total] = i; out[total++] = tmp[j]; }
        }
    }

    if (verbose)
        for (int i = 0; i < g_candCount; i++)
            if (g_cand[i].lastCount || g_candCount <= 12)
                bplog("  cand[%d] n=%-3d%s %s", i, g_cand[i].lastCount,
                      g_cand[i].nullRoot ? " (null)" : "", g_cand[i].desc);

    if (live != g_bestCand) {
        g_bestCand = live;
        bplog("merging %d populated container(s) -> %d entities", live, total);
    }
    return total;
}

// ---------------------------------------------------------------------------
// camera
// ---------------------------------------------------------------------------
static bool resolve_camera() {
    void *ue = unity_image();
    if (!ue) return false;
    void *cls = g_il2.class_from_name(ue, "UnityEngine", "Camera");
    if (!cls) { bplog("UnityEngine.Camera not found"); return false; }

    R.mGetMain = g_il2.class_get_method_from_name(cls, "get_main", 0);
    R.mView    = g_il2.class_get_method_from_name(cls, "get_worldToCameraMatrix", 0);
    R.mProj    = g_il2.class_get_method_from_name(cls, "get_projectionMatrix", 0);
    R.mPixW    = g_il2.class_get_method_from_name(cls, "get_pixelWidth", 0);
    R.mPixH    = g_il2.class_get_method_from_name(cls, "get_pixelHeight", 0);
    bplog("camera: main=%p view=%p proj=%p", R.mGetMain, R.mView, R.mProj);
    return R.mGetMain && R.mView && R.mProj;
}

/**
 * Re-fetched every poll rather than cached. A cached Camera whose native object
 * has been swapped out — respawn, death cam, scene change — keeps a non-null
 * m_CachedPtr and happily returns a view matrix frozen at the moment it died,
 * which projects every entity from a viewpoint the player left long ago.
 */
static void *camera_object() {
    R.camObj = invoke(R.mGetMain, nullptr, nullptr);
    return R.camObj;
}

static Mat4 mat_mul(const Mat4 &a, const Mat4 &b) {
    Mat4 r{};
    for (int c = 0; c < 4; c++)
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = s;
        }
    return r;
}

/** Emits normalised viewport coordinates in [0,1], not pixels. The game can
 *  render at a different resolution from the window it is presented in — this
 *  run reported a 1800x810 camera inside a much larger view — so the overlay
 *  scales by its own size and the two can never drift apart. */
static bool world_to_screen(const Mat4 &vp, Vec3 w, float *nx, float *ny) {
    float cx = vp.m[0] * w.x + vp.m[4] * w.y + vp.m[8]  * w.z + vp.m[12];
    float cy = vp.m[1] * w.x + vp.m[5] * w.y + vp.m[9]  * w.z + vp.m[13];
    float cw = vp.m[3] * w.x + vp.m[7] * w.y + vp.m[11] * w.z + vp.m[15];
    if (cw < 0.01f) return false;
    *nx = cx / cw * 0.5f + 0.5f;
    *ny = 1.0f - (cy / cw * 0.5f + 0.5f);
    return true;
}

/**
 * Joint positions for the skeleton, synthesised from the capsule and the look
 * direction. The remote entities carry no GameObject and no SkinnedMeshRenderer
 * — both read null — so there are no real bone transforms to walk; this is a
 * rig posed from position and facing, not the animated pose.
 */
static void build_joints(Vec3 p, Vec3 dir, Vec3 *j) {
    float len = sqrtf(dir.x * dir.x + dir.z * dir.z);
    Vec3 f = (len > 0.001f) ? Vec3{dir.x / len, 0, dir.z / len} : Vec3{0, 0, 1};
    Vec3 r{f.z, 0, -f.x};

    const float H = PLAYER_HEIGHT;
    Vec3 head {p.x,               p.y + H,          p.z};
    Vec3 neck {p.x,               p.y + H * 0.86f,  p.z};
    Vec3 chest{p.x,               p.y + H * 0.70f,  p.z};
    Vec3 hip  {p.x,               p.y + H * 0.52f,  p.z};

    float sh = H * 0.13f, hw = H * 0.08f, arm = H * 0.34f;
    j[0]  = head;
    j[1]  = neck;
    j[2]  = chest;
    j[3]  = hip;
    j[4]  = Vec3{neck.x - r.x * sh, neck.y, neck.z - r.z * sh};              // L shoulder
    j[5]  = Vec3{neck.x + r.x * sh, neck.y, neck.z + r.z * sh};              // R shoulder
    j[6]  = Vec3{j[4].x + f.x * arm * 0.4f, j[4].y - arm, j[4].z + f.z * arm * 0.4f};
    j[7]  = Vec3{j[5].x + f.x * arm * 0.4f, j[5].y - arm, j[5].z + f.z * arm * 0.4f};
    j[8]  = Vec3{hip.x - r.x * hw, hip.y, hip.z - r.z * hw};                 // L hip
    j[9]  = Vec3{hip.x + r.x * hw, hip.y, hip.z + r.z * hw};                 // R hip
    j[10] = Vec3{p.x - r.x * hw, p.y, p.z - r.z * hw};                       // L foot
    j[11] = Vec3{p.x + r.x * hw, p.y, p.z + r.z * hw};                       // R foot
}

/** Camera forward in world space. Unity's view matrix looks down -Z. */
static Vec3 camera_forward(const Mat4 &v) {
    return Vec3{-v.m[2], -v.m[6], -v.m[10]};
}

static float deg_yaw(Vec3 f)   { return atan2f(f.x, f.z) * 57.29578f; }
static float deg_pitch(Vec3 f) {
    float y = f.y;
    if (y > 1) y = 1;
    if (y < -1) y = -1;
    return -asinf(y) * 57.29578f;
}

/** Signed shortest way round from a to b, in degrees. */
static float angle_delta(float a, float b) {
    float d = fmodf(b - a + 540.0f, 360.0f) - 180.0f;
    return d;
}

/** Camera world position, recovered from the rigid view transform: -R^T * t. */
static Vec3 camera_pos(const Mat4 &v) {
    float t0 = v.m[12], t1 = v.m[13], t2 = v.m[14];
    Vec3 c;
    c.x = -(v.m[0] * t0 + v.m[1] * t1 + v.m[2]  * t2);
    c.y = -(v.m[4] * t0 + v.m[5] * t1 + v.m[6]  * t2);
    c.z = -(v.m[8] * t0 + v.m[9] * t1 + v.m[10] * t2);
    return c;
}

// ---------------------------------------------------------------------------
// aim actuation
// ---------------------------------------------------------------------------
// Nothing in the entity layout drives the local view — 0xF0 and 0xEC are
// replicated data for *other* players. The fields that steer the camera live on
// some local controller component whose name we do not know, so they are found
// by correlation instead: sample every float field on every live component and
// keep the ones that track the camera's own yaw and pitch across several
// rotations. A field may store the angle negated or offset by half a turn, so
// each of those forms is tested and the matching one is inverted on write.

#define AIM_MAX_CAND 3000
#define AIM_LOCK_HITS 6

struct AimCand {
    void  *obj;
    size_t off;
    int8_t formYaw, formPitch;
    int8_t hitsYaw, hitsPitch;
    float  accYaw, accPitch;     // camera travel survived while still matching
    bool   dead;                 // failed the live write check, never offer again
};

static AimCand g_aimCand[AIM_MAX_CAND];
static int     g_aimCandN = 0;
static bool    g_aimCollected = false;

static void  *g_yawObj, *g_pitchObj;
static size_t g_yawOff, g_pitchOff;
static int8_t g_yawForm = -1, g_pitchForm = -1;
static bool   g_aimVerified = false;

// form: 0:v  1:-v  2:v+180  3:v-180  4:-v+180  5:-v-180
static float apply_form(int form, float v) {
    switch (form) {
        case 0: return v;
        case 1: return -v;
        case 2: return v + 180.0f;
        case 3: return v - 180.0f;
        case 4: return -v + 180.0f;
        default: return -v - 180.0f;
    }
}

/** Inverse of apply_form: what to store so the angle reads back as `want`. */
static float unapply_form(int form, float want) {
    switch (form) {
        case 0: return want;
        case 1: return -want;
        case 2: return want - 180.0f;
        case 3: return want + 180.0f;
        case 4: return -(want - 180.0f);
        default: return -(want + 180.0f);
    }
}

static void aim_collect() {
    if (g_aimCollected) return;
    void *ue = unity_image();
    void *mb = ue ? g_il2.class_from_name(ue, "UnityEngine", "MonoBehaviour") : nullptr;
    if (!mb) return;
    void *arr = find_objects(mb);
    if (!arr) return;

    size_t n = rd<uint64_t>(arr, 0x18);
    if (n > 2048) n = 2048;
    void **objs = (void **)IL2CPP_ARRAY_DATA(arr);

    g_aimCandN = 0;
    for (size_t i = 0; i < n && g_aimCandN < AIM_MAX_CAND; i++) {
        if (!objs[i]) continue;
        void *cls = rd<void *>(objs[i], 0);
        if (!cls) continue;

        void *iter = nullptr, *f;
        while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr && g_aimCandN < AIM_MAX_CAND) {
            if (g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC) continue;
            char *tn = g_il2.type_get_name(g_il2.field_get_type(f));
            if (!tn) continue;
            if (!strcmp(tn, "System.Single")) {
                AimCand &c = g_aimCand[g_aimCandN++];
                c.obj = objs[i];
                c.off = g_il2.field_get_offset(f);
                c.formYaw = c.formPitch = 0;
                c.hitsYaw = c.hitsPitch = 0;
                c.accYaw = c.accPitch = 0;
                c.dead = false;
            }
            g_il2.free(tn);
        }
    }
    g_aimCollected = true;
    bplog("aim: %d float fields under observation across %zu components", g_aimCandN, n);
}

/** Call once per tick with the camera's true angles. Locks on when a field has
 *  tracked them through enough distinct rotations to not be a coincidence. */
static void aim_correlate(float yaw, float pitch) {
    static float lastYaw = 0, lastPitch = 0;
    static bool  have = false;

    if (g_yawForm >= 0 && g_pitchForm >= 0) return;
    aim_collect();
    if (!g_aimCandN) return;

    float dYaw = have ? angle_delta(lastYaw, yaw) : 0;
    float dPitch = have ? pitch - lastPitch : 0;
    bool first = !have;
    lastYaw = yaw; lastPitch = pitch; have = true;
    if (first) return;

    // Each axis is judged only while that axis is actually turning. Gating on
    // "either axis moved" is what let a field frozen at zero pass as yaw: during
    // pitch-only movement the yaw test still ran, and a constant matched a yaw
    // that was equally constant.
    bool yawMoved   = fabsf(dYaw) > 3.0f;
    bool pitchMoved = fabsf(dPitch) > 2.0f;
    if (!yawMoved && !pitchMoved) return;

    for (int i = 0; i < g_aimCandN; i++) {
        AimCand &c = g_aimCand[i];
        if (c.dead) continue;
        float v = rd<float>(c.obj, c.off);
        if (!(v == v) || fabsf(v) > 100000.0f) {
            c.hitsYaw = c.hitsPitch = 0;
            c.accYaw = c.accPitch = 0;
            continue;
        }

        if (g_yawForm < 0 && yawMoved) {
            bool hit = false;
            for (int form = 0; form < 6; form++)
                if (fabsf(angle_delta(apply_form(form, v), yaw)) < 1.5f) {
                    if (c.hitsYaw == 0) c.formYaw = (int8_t)form;
                    if (c.formYaw == form) { hit = true; break; }
                }
            if (hit) { c.hitsYaw++; c.accYaw += fabsf(dYaw); }
            else     { c.hitsYaw = 0; c.accYaw = 0; }

            // Enough samples *and* enough travel: a field has to follow the
            // camera through a real sweep, not agree with it once.
            if (c.hitsYaw >= AIM_LOCK_HITS && c.accYaw >= 45.0f) {
                g_yawObj = c.obj; g_yawOff = c.off; g_yawForm = c.formYaw;
                bplog("aim: yaw field locked at %p+0x%zx form=%d after %.0f deg",
                      c.obj, c.off, c.formYaw, c.accYaw);
            }
        }

        if (g_pitchForm < 0 && pitchMoved) {
            // Never accept the same address for both axes.
            if (g_yawForm >= 0 && c.obj == g_yawObj && c.off == g_yawOff) continue;

            bool hit = false;
            for (int form = 0; form < 2; form++)
                if (fabsf(angle_delta(apply_form(form, v), pitch)) < 1.5f) {
                    if (c.hitsPitch == 0) c.formPitch = (int8_t)form;
                    if (c.formPitch == form) { hit = true; break; }
                }
            if (hit) { c.hitsPitch++; c.accPitch += fabsf(dPitch); }
            else     { c.hitsPitch = 0; c.accPitch = 0; }

            if (c.hitsPitch >= AIM_LOCK_HITS && c.accPitch >= 15.0f) {
                g_pitchObj = c.obj; g_pitchOff = c.off; g_pitchForm = c.formPitch;
                bplog("aim: pitch field locked at %p+0x%zx form=%d after %.0f deg",
                      c.obj, c.off, c.formPitch, c.accPitch);
            }
        }
    }
}

static void aim_forget(const char *why) {
    bplog("aim: dropping locked fields (%s), resuming search", why);
    for (int i = 0; i < g_aimCandN; i++) {
        AimCand &c = g_aimCand[i];
        if ((c.obj == g_yawObj && c.off == g_yawOff) ||
            (c.obj == g_pitchObj && c.off == g_pitchOff)) c.dead = true;
        c.hitsYaw = c.hitsPitch = 0;
        c.accYaw = c.accPitch = 0;
    }
    g_yawForm = g_pitchForm = -1;
    g_yawObj = g_pitchObj = nullptr;
    g_aimVerified = false;
}

static bool aim_ready() { return g_yawForm >= 0 && g_pitchForm >= 0 && g_aimVerified; }

static void aim_write(float yaw, float pitch) {
    if (g_yawForm >= 0 && yaw == yaw) {
        float v = unapply_form(g_yawForm, yaw);
        memcpy((uint8_t *)g_yawObj + g_yawOff, &v, sizeof(float));
    }
    if (g_pitchForm >= 0 && pitch == pitch) {   // NaN means "leave this axis"
        float v = unapply_form(g_pitchForm, pitch);
        memcpy((uint8_t *)g_pitchObj + g_pitchOff, &v, sizeof(float));
    }
}

/**
 * Correlation only proves a field mirrors the camera; it cannot prove writing to
 * it steers anything. So before the aimbot is allowed to drive, nudge the view a
 * few degrees and confirm the camera actually followed. A field that reads right
 * but ignores writes gets blacklisted and the search resumes, instead of the
 * aimbot flailing against a value the game overwrites.
 */
static void aim_verify(float camYaw) {
    static int    phase = 0;
    static float  wanted = 0, before = 0;
    static double at = 0;

    if (phase == 0) {
        before = camYaw;
        wanted = camYaw + 6.0f;
        aim_write(wanted, 0.0f / 0.0f);   // pitch left alone during the probe
        at = now_s();
        phase = 1;
        return;
    }

    float moved = angle_delta(before, camYaw);
    if (moved > 2.0f) {
        g_aimVerified = true;
        phase = 0;
        bplog("aim: write confirmed, camera followed %.1f deg", moved);
    } else if (now_s() - at > 0.6) {
        phase = 0;
        aim_forget("write had no effect on the camera");
    }
}

// ---------------------------------------------------------------------------
// poller
// ---------------------------------------------------------------------------
static void *poll_thread(void *) {
    log_open();
    bplog("poller thread up");

    void *h = nullptr;
    for (int i = 0; i < 600 && !h; i++) {
        h = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (!h) usleep(200 * 1000);
    }
    if (!h) { set_status(0, "libil2cpp.so never loaded"); return nullptr; }
    if (!il2cpp_bind(h)) { set_status(0, "il2cpp api bind failed"); return nullptr; }
    bplog("libil2cpp bound at %p", h);

    sleep(4);
    g_il2.thread_attach(g_il2.domain_get());
    bplog("thread attached to domain");

    set_status(1, "collecting images");
    for (int tries = 0; g_imageCount == 0 && tries < 30; tries++) {
        collect_images();
        if (g_imageCount == 0) sleep(2);
    }

    set_status(1, "scanning for entity class");
    for (int tries = 0; !R.playerCls; tries++) {
        if (find_player_class()) break;
        if (tries == 4) { bplog("rescanning images"); g_imageCount = 0; collect_images(); }
        sleep(3);
    }
    set_status(1, "entity class %s", R.playerName);

    if (!resolve_camera()) bplog("camera api unresolved — nothing can be projected");

    {
        void *ue = unity_image();
        if (ue) R.objectCls = g_il2.class_from_name(ue, "UnityEngine", "Object");
        bplog("UnityEngine.Object = %p", R.objectCls);
    }

    set_status(1, "collecting containers");
    collect_static_containers();
    collect_scene_containers();
    bplog("%d container candidate(s)", g_candCount);

    void *ents[MAX_ENT];
    int   entCand[MAX_ENT];
    double lastDiag = 0, lastSweep = now_s();
    float prevPitch = 0;
    bool  havePrevPitch = false;

    for (;;) {
        bool diag = now_s() - lastDiag > 3.0;
        if (diag) lastDiag = now_s();

        int n = read_entities(ents, entCand, MAX_ENT, diag);
        if (n == 0) {
            publish(nullptr, 0);
            // Holders spawn with the match, so keep re-sweeping the scene: a
            // list that does not exist at menu time will appear later.
            if (now_s() - lastSweep > 3.0) {
                lastSweep = now_s();
                collect_scene_containers();
                collect_static_containers();
            }
            set_status(3, "no players yet (%d candidates)", g_candCount);
            usleep(300 * 1000);
            continue;
        }
        if (g_state != 2) set_status(2, "live");

        void *cam = camera_object();
        void *vBox = cam ? invoke(R.mView, cam, nullptr) : nullptr;
        void *pBox = cam ? invoke(R.mProj, cam, nullptr) : nullptr;
        if (!vBox || !pBox) {
            // Camera.main goes null on death and between rounds. Keeping the
            // stale frame here is what made boxes linger after a kill.
            publish(nullptr, 0);
            if (diag) bplog("entities=%d but camera unavailable (cam=%p)", n, cam);
            usleep(200 * 1000);
            continue;
        }

        Mat4 view = rd<Mat4>(unbox(vBox), 0);
        Mat4 proj = rd<Mat4>(unbox(pBox), 0);
        Mat4 vp   = mat_mul(proj, view);

        // The eye comes from the view matrix, not from an entity: the local
        // player's replicated position is never filled in (it stays 0,0,0).
        Vec3 eye = camera_pos(view);
        Vec3 fwd = camera_forward(view);
        float camYaw = deg_yaw(fwd), camPitch = deg_pitch(fwd);

        Config C = cfg();
        bool wantAim = C.aimbot || C.rcs;
        if (wantAim) aim_correlate(camYaw, camPitch);
        bool probing = wantAim && g_yawForm >= 0 && g_pitchForm >= 0 && !g_aimVerified;
        if (probing) aim_verify(camYaw);

        // Team: the roster is one container per side, and the entity with no
        // replicated position is us, so whichever container holds it is ours.
        int ownCand = -1;
        if (C.teamCheck)
            for (int i = 0; i < n; i++) {
                Vec3 p = rd<Vec3>(ents[i], R.oPos);
                if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f &&
                    g_cand[entCand[i]].kind != 3) { ownCand = entCand[i]; break; }
            }

        EntityView tmp[MAX_ENT];
        int out = 0;
        int skipZero = 0, skipProj = 0, skipTeam = 0;

        // best aimbot target this tick
        float bestAngle = 1e9f;
        Vec3  bestPoint{0, 0, 0};
        bool  haveTarget = false;

        for (int i = 0; i < n && out < MAX_ENT; i++) {
            void *e = ents[i];

            if (diag && i < 8) {
                char nm[40];
                il2cpp_string_to_utf8(rd<void *>(e, R.oName), nm, sizeof(nm));
                Vec3 p  = rd<Vec3>(e, R.oPos);
                Vec3 nf = rd<Vec3>(e, OFF_NETPOS_FROM);
                Vec3 nt = rd<Vec3>(e, OFF_NETPOS_TO);
                float sx = 0, sy = 0;
                world_to_screen(vp, p, &sx, &sy);
                bplog("  ent[%d] '%s' hp=%d cur=(%.2f,%.2f,%.2f) from=(%.2f,%.2f,%.2f) "
                      "to=(%.2f,%.2f,%.2f) go=%p screen=(%.3f,%.3f)",
                      i, nm, rd<int32_t>(e, R.oHp), p.x, p.y, p.z,
                      nf.x, nf.y, nf.z, nt.x, nt.y, nt.z,
                      rd<void *>(e, OFF_GAMEOBJECT), sx, sy);
            }

            // Only one filter, and it is the one the data supports: an entity
            // with no replicated position cannot be drawn. That covers the local
            // player and anyone not yet spawned. NMAMove marks bots, not self,
            // so keying off it deleted every bot from the overlay.
            Vec3 p = rd<Vec3>(e, R.oPos);
            if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f) { skipZero++; continue; }
            if (ownCand >= 0 && entCand[i] == ownCand) { skipTeam++; continue; }

            int hp = rd<int32_t>(e, R.oHp);
            Vec3 head{p.x, p.y + PLAYER_HEIGHT, p.z};

            float fx, fy, hx, hy;
            if (!world_to_screen(vp, p, &fx, &fy) ||
                !world_to_screen(vp, head, &hx, &hy)) { skipProj++; continue; }

            Vec3 d = rd<Vec3>(e, R.oDir);
            Vec3 tip{p.x + d.x * 2.0f, p.y + PLAYER_HEIGHT * 0.6f + d.y * 2.0f, p.z + d.z * 2.0f};
            float tx = fx, ty = fy;
            world_to_screen(vp, tip, &tx, &ty);

            float dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
            float dist = sqrtf(dx * dx + dy * dy + dz * dz);

            EntityView &v = tmp[out];
            v.data[0]  = fx;
            v.data[1]  = fy;
            v.data[2]  = hx;
            v.data[3]  = hy;
            v.data[4]  = PLAYER_WIDTH_RATIO;
            v.data[5]  = (float)hp;
            v.data[6]  = (float)rd<int32_t>(e, R.oArm);
            v.data[7]  = dist;
            v.data[8]  = (float)rd<int32_t>(e, R.oKill);
            v.data[9]  = (float)rd<int32_t>(e, R.oDeath);
            v.data[10] = (float)rd<int32_t>(e, R.oScore);
            v.data[11] = hp > 0 ? 1.0f : 0.0f;
            v.data[12] = tx;
            v.data[13] = ty;

            Vec3 joints[JOINTS];
            build_joints(p, d, joints);

            // Aimbot target selection. Head/chest/hip map onto the same rig the
            // skeleton uses; "nearest" takes whichever joint is closest to where
            // the view already points, which needs the least correction.
            if (C.aimbot && hp > 0) {
                static const int BONE_JOINT[3] = { 0, 2, 3 };
                int lo = (C.bone == 3) ? 0 : BONE_JOINT[C.bone < 3 ? C.bone : 0];
                int hi = (C.bone == 3) ? JOINTS : lo + 1;

                for (int k = lo; k < hi; k++) {
                    Vec3 t = joints[k];
                    float dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
                    float m = sqrtf(dx * dx + dy * dy + dz * dz);
                    if (m < 0.01f) continue;
                    float cosA = (fwd.x * dx + fwd.y * dy + fwd.z * dz) / m;
                    if (cosA > 1) cosA = 1;
                    if (cosA < -1) cosA = -1;
                    float ang = acosf(cosA) * 57.29578f;
                    if (ang > C.fov * 0.5f) continue;
                    if (ang < bestAngle) { bestAngle = ang; bestPoint = t; haveTarget = true; }
                }
            }

            for (int k = 0; k < JOINTS; k++) {
                float jx = fx, jy = fy;
                world_to_screen(vp, joints[k], &jx, &jy);
                v.data[14 + k * 2]     = jx;
                v.data[14 + k * 2 + 1] = jy;
            }

            il2cpp_string_to_utf8(rd<void *>(e, R.oName), v.name, sizeof(v.name));
            out++;
        }

        // ---- aim actuation -------------------------------------------------
        float wantYaw = camYaw, wantPitch = camPitch;
        bool  writeAim = false;

        if (C.rcs && havePrevPitch && aim_ready()) {
            // Recoil pushes the view up, which drives pitch negative. Only a
            // kick sharper than deliberate look-up input is fought, and only
            // downward, so ordinary aiming is left alone.
            float dp = camPitch - prevPitch;
            if (dp < -0.15f) {
                wantPitch = camPitch - dp * (C.rcsPower / 100.0f);
                writeAim = true;
            }
        }

        if (C.aimbot && haveTarget && aim_ready()) {
            float dx = bestPoint.x - eye.x, dy = bestPoint.y - eye.y, dz = bestPoint.z - eye.z;
            float m = sqrtf(dx * dx + dy * dy + dz * dz);
            if (m > 0.01f) {
                Vec3 want{dx / m, dy / m, dz / m};
                float ty = deg_yaw(want), tp = deg_pitch(want);
                float k = C.speed / 100.0f;
                if (k > 1) k = 1;
                if (k < 0.01f) k = 0.01f;
                wantYaw   = camYaw   + angle_delta(camYaw, ty) * k;
                wantPitch = wantPitch + (tp - wantPitch) * k;
                writeAim = true;
            }
        }

        if (writeAim && !probing) aim_write(wantYaw, wantPitch);
        prevPitch = camPitch;
        havePrevPitch = true;

        if (diag)
            bplog("entities=%d drawn=%d (skip zeroPos=%d team=%d behind=%d) "
                  "eye=(%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f aimFields=%s target=%s",
                  n, out, skipZero, skipTeam, skipProj, eye.x, eye.y, eye.z,
                  camYaw, camPitch, aim_ready() ? "locked" : "searching",
                  haveTarget ? "yes" : "no");

        publish(tmp, out);

        usleep(8 * 1000);
    }
    return nullptr;
}

static void publish(const struct EntityView *src, int n) {
    if (n < 0) n = 0;
    if (n > MAX_ENT) n = MAX_ENT;
    pthread_mutex_lock(&g_lock);
    if (src && n) memcpy(g_ent, src, sizeof(EntityView) * n);
    g_count = n;
    if (n && g_state != 2) { g_state = 2; snprintf(g_status, sizeof(g_status), "live"); }
    pthread_mutex_unlock(&g_lock);
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT void JNICALL
Java_com_esp_Native_setLog(JNIEnv *env, jclass, jstring path) {
    if (!path) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        snprintf(g_logPath, sizeof(g_logPath), "%s", p);
        env->ReleaseStringUTFChars(path, p);
        log_open();
    }
}

JNIEXPORT void JNICALL
Java_com_esp_Native_config(JNIEnv *, jclass, jboolean teamCheck, jboolean aimbot,
                           jfloat fov, jfloat speed, jint bone,
                           jboolean rcs, jfloat rcsPower) {
    pthread_mutex_lock(&g_cfgLock);
    g_cfg.teamCheck = teamCheck;
    g_cfg.aimbot    = aimbot;
    g_cfg.fov       = fov;
    g_cfg.speed     = speed;
    g_cfg.bone      = bone;
    g_cfg.rcs       = rcs;
    g_cfg.rcsPower  = rcsPower;
    pthread_mutex_unlock(&g_cfgLock);
}

JNIEXPORT jboolean JNICALL
Java_com_esp_Native_aimReady(JNIEnv *, jclass) { return aim_ready(); }

JNIEXPORT void JNICALL
Java_com_esp_Native_viewport(JNIEnv *, jclass, jint w, jint h) {
    if (w > 1 && h > 1) { g_screenW = (float)w; g_screenH = (float)h; }
}

JNIEXPORT void JNICALL
Java_com_esp_Native_start(JNIEnv *env, jclass cls, jint w, jint h) {
    static bool started = false;
    Java_com_esp_Native_viewport(env, cls, w, h);
    if (started) return;
    started = true;
    pthread_t t;
    pthread_create(&t, nullptr, poll_thread, nullptr);
    pthread_detach(t);
}

JNIEXPORT jint JNICALL
Java_com_esp_Native_state(JNIEnv *, jclass) {
    pthread_mutex_lock(&g_lock);
    int s = g_state;
    pthread_mutex_unlock(&g_lock);
    return s;
}

JNIEXPORT jstring JNICALL
Java_com_esp_Native_status(JNIEnv *env, jclass) {
    char buf[192];
    pthread_mutex_lock(&g_lock);
    snprintf(buf, sizeof(buf), "%s", g_status);
    pthread_mutex_unlock(&g_lock);
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_esp_Native_fetch(JNIEnv *env, jclass, jfloatArray outData, jobjectArray outNames) {
    static float flat[MAX_ENT * STRIDE];
    static char  names[MAX_ENT][40];

    pthread_mutex_lock(&g_lock);
    int n = g_count > MAX_ENT ? MAX_ENT : g_count;
    for (int i = 0; i < n; i++) {
        memcpy(&flat[i * STRIDE], g_ent[i].data, sizeof(float) * STRIDE);
        memcpy(names[i], g_ent[i].name, sizeof(names[i]));
    }
    pthread_mutex_unlock(&g_lock);

    if (n > 0) {
        env->SetFloatArrayRegion(outData, 0, n * STRIDE, flat);
        for (int i = 0; i < n; i++) {
            jstring s = env->NewStringUTF(names[i]);
            env->SetObjectArrayElement(outNames, i, s);
            env->DeleteLocalRef(s);
        }
    }
    return n;
}

}  // extern "C"
