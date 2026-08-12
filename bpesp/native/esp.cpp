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
#define STRIDE  14
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

static void set_status(int st, const char *fmt, ...) {
    char tmp[192];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);

    pthread_mutex_lock(&g_lock);
    g_state = st;
    snprintf(g_status, sizeof(g_status), "%s", tmp);
    pthread_mutex_unlock(&g_lock);
    bplog("state=%d %s", st, tmp);
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
static int container_kind(void *type) {
    char *tn = g_il2.type_get_name(type);
    if (!tn) return 0;

    int kind = 0;
    size_t len = strlen(tn), pn = strlen(R.playerName);
    if (len > 2 && !strcmp(tn + len - 2, "[]")) {
        if (len - 2 >= pn && !strncmp(tn + len - 2 - pn, R.playerName, pn)) kind = 1;
    } else if (strstr(tn, "List`1<")) {
        char want[80];
        snprintf(want, sizeof(want), "<%s>", R.playerName);
        if (strstr(tn, want)) kind = 2;
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
    int    kind     = 0;         // 1 = T[], 2 = List<T>
    int    lastCount = 0;
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

/** Resolves one candidate to its element array and copies out non-null entries. */
static int eval_cand(Cand &c, void *base, void **out, int cap) {
    if (!base) return 0;
    void *root = rd<void *>(base, c.rootOff);
    if (!root) return 0;
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
    for (int i = 0; i < count; i++) if (data[i]) out[got++] = data[i];
    return got;
}

/** For a scene-resident holder, tries every live instance and keeps the best. */
static int eval_scene_cand(Cand &c, void **out, int cap) {
    void *arr = find_objects(c.cls);
    if (!arr) return 0;
    size_t n = rd<uint64_t>(arr, 0x18);
    if (n > 256) n = 256;
    void **objs = (void **)IL2CPP_ARRAY_DATA(arr);

    int best = 0;
    void *tmp[MAX_ENT];
    for (size_t i = 0; i < n; i++) {
        if (!objs[i]) continue;
        int got = eval_cand(c, objs[i], tmp, cap);
        if (got > best) { best = got; memcpy(out, tmp, sizeof(void *) * got); }
    }
    return best;
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

/** Picks the candidate holding the most entities right now. */
static int read_entities(void **out, int cap, bool verbose) {
    int bestCount = 0, bestIdx = -1;
    void *tmp[MAX_ENT];

    for (int i = 0; i < g_candCount; i++) {
        int got = eval_any(g_cand[i], tmp, cap);
        g_cand[i].lastCount = got;
        if (got > bestCount) {
            bestCount = got;
            bestIdx = i;
            memcpy(out, tmp, sizeof(void *) * got);
        }
    }

    if (verbose) {
        for (int i = 0; i < g_candCount; i++)
            bplog("  cand[%d] n=%-3d %s", i, g_cand[i].lastCount, g_cand[i].desc);
    }
    if (bestIdx >= 0 && bestIdx != g_bestCand) {
        g_bestCand = bestIdx;
        bplog("using container: %s (%d entities)", g_cand[bestIdx].desc, bestCount);
    }
    return bestCount;
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

static void *camera_object() {
    if (R.camObj && rd<void *>(R.camObj, 0x10)) return R.camObj;
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

static bool world_to_screen(const Mat4 &vp, Vec3 w, float sw, float sh,
                            float *sx, float *sy) {
    float cx = vp.m[0] * w.x + vp.m[4] * w.y + vp.m[8]  * w.z + vp.m[12];
    float cy = vp.m[1] * w.x + vp.m[5] * w.y + vp.m[9]  * w.z + vp.m[13];
    float cw = vp.m[3] * w.x + vp.m[7] * w.y + vp.m[11] * w.z + vp.m[15];
    if (cw < 0.01f) return false;
    *sx = (cx / cw * 0.5f + 0.5f) * sw;
    *sy = (1.0f - (cy / cw * 0.5f + 0.5f)) * sh;
    return true;
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
    double lastDiag = 0, lastSweep = now_s();

    for (;;) {
        bool diag = now_s() - lastDiag > 3.0;
        if (diag) lastDiag = now_s();

        int n = read_entities(ents, MAX_ENT, diag);
        if (n == 0) {
            // Holders spawn with the match, so keep re-sweeping the scene: a
            // list that does not exist at menu time will appear later.
            if (now_s() - lastSweep > 8.0) {
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
            if (diag) bplog("entities=%d but camera unavailable (cam=%p)", n, cam);
            usleep(200 * 1000);
            continue;
        }

        Mat4 view = rd<Mat4>(unbox(vBox), 0);
        Mat4 proj = rd<Mat4>(unbox(pBox), 0);
        Mat4 vp   = mat_mul(proj, view);

        float sw = g_screenW, sh = g_screenH;
        if (R.mPixW && R.mPixH) {
            void *bw = invoke(R.mPixW, cam, nullptr), *bh = invoke(R.mPixH, cam, nullptr);
            if (bw && bh) {
                float w = (float)rd<int32_t>(unbox(bw), 0);
                float hgt = (float)rd<int32_t>(unbox(bh), 0);
                if (w > 1 && hgt > 1) { sw = w; sh = hgt; }
            }
        }
        if (sw < 1 || sh < 1) {
            if (diag) bplog("no viewport size (camera and java both silent)");
            usleep(200 * 1000);
            continue;
        }

        Vec3 eye{0, 0, 0};
        bool haveEye = false;
        for (int i = 0; i < n; i++)
            if (rd<void *>(ents[i], R.oMove)) {
                eye = rd<Vec3>(ents[i], R.oPos);
                haveEye = true;
                break;
            }

        EntityView tmp[MAX_ENT];
        int out = 0;
        int skipLocal = 0, skipNoTf = 0, skipZero = 0, skipProj = 0;

        for (int i = 0; i < n && out < MAX_ENT; i++) {
            void *e = ents[i];

            if (diag && i < 4) {
                char nm[40];
                il2cpp_string_to_utf8(rd<void *>(e, R.oName), nm, sizeof(nm));
                Vec3 p = rd<Vec3>(e, R.oPos);
                bplog("  ent[%d] name='%s' hp=%d armor=%d pos=(%.2f,%.2f,%.2f) "
                      "move=%p noTf=%d", i, nm,
                      rd<int32_t>(e, R.oHp), rd<int32_t>(e, R.oArm),
                      p.x, p.y, p.z, rd<void *>(e, R.oMove), rd<uint8_t>(e, R.oNoTf));
            }

            if (rd<void *>(e, R.oMove)) { skipLocal++; continue; }
            if (rd<uint8_t>(e, R.oNoTf)) { skipNoTf++; continue; }

            Vec3 p = rd<Vec3>(e, R.oPos);
            if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f) { skipZero++; continue; }

            int hp = rd<int32_t>(e, R.oHp);
            Vec3 head{p.x, p.y + PLAYER_HEIGHT, p.z};

            float fx, fy, hx, hy;
            if (!world_to_screen(vp, p, sw, sh, &fx, &fy) ||
                !world_to_screen(vp, head, sw, sh, &hx, &hy)) { skipProj++; continue; }

            Vec3 d = rd<Vec3>(e, R.oDir);
            Vec3 tip{p.x + d.x * 2.0f, p.y + PLAYER_HEIGHT * 0.6f + d.y * 2.0f, p.z + d.z * 2.0f};
            float tx = fx, ty = fy;
            world_to_screen(vp, tip, sw, sh, &tx, &ty);

            float dist = 0;
            if (haveEye) {
                float dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
                dist = sqrtf(dx * dx + dy * dy + dz * dz);
            }

            EntityView &v = tmp[out];
            v.data[0]  = fx;
            v.data[1]  = fy;
            v.data[2]  = hx;
            v.data[3]  = hy;
            v.data[4]  = fabsf(fy - hy) * PLAYER_WIDTH_RATIO;
            v.data[5]  = (float)hp;
            v.data[6]  = (float)rd<int32_t>(e, R.oArm);
            v.data[7]  = dist;
            v.data[8]  = (float)rd<int32_t>(e, R.oKill);
            v.data[9]  = (float)rd<int32_t>(e, R.oDeath);
            v.data[10] = (float)rd<int32_t>(e, R.oScore);
            v.data[11] = hp > 0 ? 1.0f : 0.0f;
            v.data[12] = tx;
            v.data[13] = ty;
            il2cpp_string_to_utf8(rd<void *>(e, R.oName), v.name, sizeof(v.name));
            out++;
        }

        if (diag)
            bplog("entities=%d drawn=%d (skip local=%d noTf=%d zeroPos=%d behind=%d) "
                  "viewport=%.0fx%.0f eye=%d",
                  n, out, skipLocal, skipNoTf, skipZero, skipProj, sw, sh, (int)haveEye);

        pthread_mutex_lock(&g_lock);
        memcpy(g_ent, tmp, sizeof(EntityView) * out);
        g_count = out;
        if (g_state != 2) { g_state = 2; snprintf(g_status, sizeof(g_status), "live"); }
        pthread_mutex_unlock(&g_lock);

        usleep(8 * 1000);
    }
    return nullptr;
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
