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
#define JOINTS  16
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
    bool  bones     = true;     // animated rigs; costs Unity calls to discover
    bool  trigger   = false;
    float triggerFov = 4.0f;    // degrees, cone counted as "on an enemy"
};

// Published for the overlay: what the aim is doing, so it can be seen rather
// than inferred from the game's behaviour.
struct AimInfo {
    float hasTarget;   // 0 or 1
    float targetX, targetY;   // normalised viewport
    float fovRadius;   // normalised half-height of the aim cone on screen
    float state;       // 0 searching, 1 locked but unproven, 2 steering
    float triggerHit;  // 0 or 1
};
static AimInfo g_aimInfo;
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
// ---------------------------------------------------------------------------
// view steering
// ---------------------------------------------------------------------------
// The view is driven by writing the camera Transform's euler angles, not by
// finding a float that mirrors them. Correlating fields located several that
// tracked the camera faithfully, but writing to any of them moved nothing —
// they are outputs the game recomputes from input every frame. The transform is
// the thing the game itself renders from, so setting it steers by construction
// and needs no discovery, no probe and no verification.
/** A destroyed UnityEngine.Object keeps its managed shell but zeroes m_CachedPtr. */
static bool unity_alive(void *obj) {
    return obj && rd<void *>(obj, 0x10) != nullptr;
}

typedef void (*TransformEuler)(void *transform, Vec3 *v);
static TransformEuler g_getEuler, g_setEuler;
static void *g_mGetTransform;
static bool  g_viewReady;

static void resolve_view() {
    void *ue = unity_image();
    if (!ue) return;

    void *(*resolve)(const char *) =
        (void *(*)(const char *))dlsym(g_il2.handle, "il2cpp_resolve_icall");
    if (resolve) {
        g_getEuler = (TransformEuler)resolve("UnityEngine.Transform::get_eulerAngles_Injected");
        g_setEuler = (TransformEuler)resolve("UnityEngine.Transform::set_eulerAngles_Injected");
    }

    void *comp = g_il2.class_from_name(ue, "UnityEngine", "Component");
    if (comp) g_mGetTransform = g_il2.class_get_method_from_name(comp, "get_transform", 0);

    g_viewReady = g_getEuler && g_setEuler && g_mGetTransform;
    bplog("view: getEuler=%p setEuler=%p get_transform=%p -> %s",
          (void *)g_getEuler, (void *)g_setEuler, g_mGetTransform,
          g_viewReady ? "ready" : "UNAVAILABLE");
}

/** Applies a change to the camera's yaw and pitch, in degrees. */
static bool steer_view(void *cam, float dYaw, float dPitch) {
    if (!g_viewReady || !cam) return false;
    void *tr = invoke(g_mGetTransform, cam, nullptr);
    if (!unity_alive(tr)) return false;

    Vec3 e{0, 0, 0};
    g_getEuler(tr, &e);
    e.x += dPitch;      // Unity: x is pitch, positive looking down
    e.y += dYaw;
    e.z = 0;            // never leave the horizon tilted
    if (e.x > 89.0f && e.x < 271.0f) e.x = (dPitch > 0) ? 89.0f : 271.0f;
    g_setEuler(tr, &e);
    return true;
}

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

// ---------------------------------------------------------------------------
// real bones
// ---------------------------------------------------------------------------
// The entity has no GameObject and no SkinnedMeshRenderer of its own — both read
// null — so the animated model cannot be reached from it. It can be reached from
// the other end: sweep the scene's skinned renderers, take each one's bone
// hierarchy, and match a rig to an entity by proximity. Bone world positions are
// then read through Transform's injected icall, which is a direct native call
// rather than a managed invoke, so it is cheap enough to do every frame.

typedef void (*TransformGetPos)(void *transform, Vec3 *out);
static TransformGetPos g_getPos;

static void *g_smrCls, *g_mGetBones, *g_mGetName;
static bool  g_boneApiTried;

// A renderer's bone mapping never changes, so it is resolved once and kept.
// Rebuilding it every couple of seconds meant hundreds of managed invokes per
// scan from a thread Unity does not own, which is what deadlocked the game.
struct RigCache {
    void *smr;
    void *bone[JOINTS];
    int   mapped;
};
static RigCache g_cache[MAX_ENT * 2];
static int      g_cacheN;

struct Rig {
    void *ent;
    void *bone[JOINTS];
};
static Rig    g_rig[MAX_ENT];
static int    g_rigN;
static double g_lastRendererScan;
static bool   g_loggedBoneNames;

static void bone_api_init() {
    if (g_boneApiTried) return;
    g_boneApiTried = true;

    g_getPos = (TransformGetPos)dlsym(g_il2.handle, "il2cpp_resolve_icall");
    if (g_getPos) {
        void *(*resolve)(const char *) = (void *(*)(const char *))g_getPos;
        g_getPos = (TransformGetPos)resolve("UnityEngine.Transform::get_position_Injected");
    }

    void *ue = unity_image();
    if (ue) {
        g_smrCls = g_il2.class_from_name(ue, "UnityEngine", "SkinnedMeshRenderer");
        if (g_smrCls) g_mGetBones = g_il2.class_get_method_from_name(g_smrCls, "get_bones", 0);
        void *objCls = g_il2.class_from_name(ue, "UnityEngine", "Object");
        if (objCls) g_mGetName = g_il2.class_get_method_from_name(objCls, "get_name", 0);
    }
    bplog("bones: getPos=%p smr=%p getBones=%p getName=%p",
          (void *)g_getPos, g_smrCls, g_mGetBones, g_mGetName);
}

static bool bone_api_ok() { return g_getPos && g_smrCls && g_mGetBones && g_mGetName; }

// Reading a position off a destroyed Transform raises a managed exception, and
// with -fno-exceptions that unwinds out of this thread and aborts the process —
// which is exactly how a respawn killed the game. Every cached Transform is
// checked with unity_alive before it is touched.
static Vec3 bone_pos(void *transform) {
    Vec3 v{0, 0, 0};
    if (unity_alive(transform) && g_getPos) g_getPos(transform, &v);
    return v;
}

static void lower(char *s) {
    for (; *s; s++) if (*s >= 'A' && *s <= 'Z') *s += 32;
}

/** Maps a bone name onto a joint slot; -1 when it is not one we draw. */
static int joint_for(const char *raw) {
    char n[64];
    snprintf(n, sizeof(n), "%s", raw ? raw : "");
    lower(n);

    bool left  = strstr(n, "left") || strstr(n, "_l") || strstr(n, ".l") || strstr(n, " l");
    bool right = strstr(n, "right") || strstr(n, "_r") || strstr(n, ".r") || strstr(n, " r");

    if (strstr(n, "head"))                          return 0;
    if (strstr(n, "neck"))                          return 1;
    if (strstr(n, "chest") || strstr(n, "spine") ||
        strstr(n, "body")  || strstr(n, "torso"))   return 2;
    if (strstr(n, "hips") || strstr(n, "pelvis"))   return 3;
    if (strstr(n, "shoulder"))                      return left ? 4 : (right ? 5 : -1);
    if (strstr(n, "elbow") || strstr(n, "forearm") ||
        strstr(n, "arm"))                           return left ? 6 : (right ? 7 : -1);
    if (strstr(n, "hand") || strstr(n, "wrist"))    return left ? 8 : (right ? 9 : -1);
    if (strstr(n, "upleg") || strstr(n, "thigh") ||
        strstr(n, "hip"))                           return left ? 10 : (right ? 11 : -1);
    if (strstr(n, "knee") || strstr(n, "leg"))      return left ? 12 : (right ? 13 : -1);
    if (strstr(n, "foot") || strstr(n, "ankle") ||
        strstr(n, "toe"))                           return left ? 14 : (right ? 15 : -1);
    return -1;
}

/**
 * Resolves renderers to bone sets. This is the expensive, Unity-touching half —
 * FindObjectsOfType plus a name lookup per bone — so it runs only when some
 * entity still lacks a rig, and never more than once every few seconds.
 */
static void scan_renderers() {
    bone_api_init();
    if (!bone_api_ok()) return;

    void *arr = find_objects(g_smrCls);
    if (!arr) return;
    size_t count = rd<uint64_t>(arr, 0x18);
    if (count > 64) count = 64;
    void **smrs = (void **)IL2CPP_ARRAY_DATA(arr);

    int added = 0;
    for (size_t i = 0; i < count && g_cacheN < (int)(sizeof(g_cache) / sizeof(g_cache[0])); i++) {
        if (!unity_alive(smrs[i])) continue;

        bool known = false;
        for (int k = 0; k < g_cacheN; k++) if (g_cache[k].smr == smrs[i]) { known = true; break; }
        if (known) continue;

        void *bonesArr = invoke(g_mGetBones, smrs[i], nullptr);
        if (!bonesArr) continue;
        size_t bn = rd<uint64_t>(bonesArr, 0x18);
        if (bn < 4 || bn > 128) continue;
        void **bones = (void **)IL2CPP_ARRAY_DATA(bonesArr);

        RigCache rc{};
        rc.smr = smrs[i];
        for (size_t b = 0; b < bn; b++) {
            if (!unity_alive(bones[b])) continue;
            void *nameObj = invoke(g_mGetName, bones[b], nullptr);
            char nm[64];
            il2cpp_string_to_utf8(nameObj, nm, sizeof(nm));
            if (!g_loggedBoneNames) bplog("bones: '%s'", nm);
            int j = joint_for(nm);
            if (j >= 0 && !rc.bone[j]) { rc.bone[j] = bones[b]; rc.mapped++; }
        }
        g_loggedBoneNames = true;
        if (rc.mapped >= 6) { g_cache[g_cacheN++] = rc; added++; }
    }
    if (added) bplog("bones: cached %d new rig(s), %d total", added, g_cacheN);
}

/**
 * Associates cached rigs with entities. Pure icall reads — no managed calls, no
 * allocation, nothing that can take a Unity lock — so this is safe to run often.
 */
static void match_rigs(void **ents, int n) {
    // Evict rigs whose renderer has been destroyed; their bones are dead too.
    int keep = 0;
    for (int c = 0; c < g_cacheN; c++)
        if (unity_alive(g_cache[c].smr)) g_cache[keep++] = g_cache[c];
    if (keep != g_cacheN) {
        bplog("bones: dropped %d destroyed rig(s), %d left", g_cacheN - keep, keep);
        g_cacheN = keep;
    }

    g_rigN = 0;
    int noAnchor = 0;
    for (int c = 0; c < g_cacheN && g_rigN < MAX_ENT; c++) {
        // Any live bone will do as the anchor. Insisting on one particular slot
        // meant a rig whose hips or spine happened to be unmapped or already
        // destroyed was dropped whole, which is how the skeleton stopped
        // animating even though the cache was healthy.
        Vec3 anchor{0, 0, 0};
        for (int k = 0; k < JOINTS && anchor.x == 0 && anchor.y == 0 && anchor.z == 0; k++) {
            static const int ORDER[JOINTS] = { 3, 2, 1, 0, 10, 11, 4, 5,
                                               12, 13, 6, 7, 14, 15, 8, 9 };
            anchor = bone_pos(g_cache[c].bone[ORDER[k]]);
        }
        if (anchor.x == 0 && anchor.y == 0 && anchor.z == 0) { noAnchor++; continue; }

        void *best = nullptr;
        float bestD = 2.5f;
        for (int e = 0; e < n; e++) {
            Vec3 p = rd<Vec3>(ents[e], R.oPos);
            if (p.x == 0 && p.y == 0 && p.z == 0) continue;
            float dx = p.x - anchor.x, dy = p.y - anchor.y, dz = p.z - anchor.z;
            float dist = sqrtf(dx * dx + dy * dy * 0.25f + dz * dz);
            if (dist < bestD) { bestD = dist; best = ents[e]; }
        }
        if (!best) continue;

        Rig &rig = g_rig[g_rigN++];
        rig.ent = best;
        memcpy(rig.bone, g_cache[c].bone, sizeof(rig.bone));
    }

    static int lastReported = -1;
    if (g_rigN != lastReported) {
        lastReported = g_rigN;
        bplog("bones: %d rig(s) matched of %d cached (%d had no live anchor)",
              g_rigN, g_cacheN, noAnchor);
    }
}

static Rig *rig_for(void *ent) {
    for (int i = 0; i < g_rigN; i++) if (g_rig[i].ent == ent) return &g_rig[i];
    return nullptr;
}

/**
 * Joint positions for the skeleton, synthesised from the capsule and the look
 * direction. Used only when no animated rig could be matched to the entity.
 */
static void build_joints(Vec3 p, Vec3 dir, float walked, float speed, Vec3 *j) {
    float len = sqrtf(dir.x * dir.x + dir.z * dir.z);
    Vec3 f = (len > 0.001f) ? Vec3{dir.x / len, 0, dir.z / len} : Vec3{0, 0, 1};
    Vec3 r{f.z, 0, -f.x};

    // The gait is driven by metres walked, which the entity already reports, so
    // the stride advances with actual movement and needs no per-entity state and
    // no clock. Amplitude follows current speed, so a standing player is still.
    float amp = speed * 2.5f;
    if (amp > 1.0f) amp = 1.0f;
    float sw = sinf(walked * 2.2f) * amp;      // leg swing, forward is positive
    float bob = (1.0f - cosf(walked * 4.4f)) * 0.5f * amp * 0.06f;

    const float H = PLAYER_HEIGHT;
    float sh = H * 0.14f, hw = H * 0.09f;
    float legF = H * 0.30f * sw, armF = H * 0.22f * sw;

    Vec3 hip  {p.x, p.y + H * 0.50f - bob, p.z};
    Vec3 chest{p.x, p.y + H * 0.70f - bob, p.z};
    Vec3 neck {p.x, p.y + H * 0.84f - bob, p.z};
    Vec3 head {p.x, p.y + H * 0.94f - bob, p.z};

    j[0] = head;
    j[1] = neck;
    j[2] = chest;
    j[3] = hip;

    j[4] = Vec3{neck.x - r.x * sh, neck.y - H * 0.02f, neck.z - r.z * sh};   // L shoulder
    j[5] = Vec3{neck.x + r.x * sh, neck.y - H * 0.02f, neck.z + r.z * sh};   // R shoulder
    // Arms counter-swing against the legs.
    j[6] = Vec3{j[4].x - f.x * armF, j[4].y - H * 0.20f, j[4].z - f.z * armF};  // L elbow
    j[7] = Vec3{j[5].x + f.x * armF, j[5].y - H * 0.20f, j[5].z + f.z * armF};  // R elbow
    j[8] = Vec3{j[6].x + f.x * H * 0.10f, j[6].y - H * 0.18f, j[6].z + f.z * H * 0.10f};
    j[9] = Vec3{j[7].x + f.x * H * 0.10f, j[7].y - H * 0.18f, j[7].z + f.z * H * 0.10f};

    j[10] = Vec3{hip.x - r.x * hw, hip.y, hip.z - r.z * hw};                 // L hip
    j[11] = Vec3{hip.x + r.x * hw, hip.y, hip.z + r.z * hw};                 // R hip
    j[12] = Vec3{j[10].x + f.x * legF * 0.5f, p.y + H * 0.26f, j[10].z + f.z * legF * 0.5f};
    j[13] = Vec3{j[11].x - f.x * legF * 0.5f, p.y + H * 0.26f, j[11].z - f.z * legF * 0.5f};
    j[14] = Vec3{j[10].x + f.x * legF, p.y, j[10].z + f.z * legF};           // L foot
    j[15] = Vec3{j[11].x - f.x * legF, p.y, j[11].z - f.z * legF};           // R foot
}

// ---------------------------------------------------------------------------
// team discovery
// ---------------------------------------------------------------------------
// There is no per-team container — one array holds the whole roster — so the
// side has to come from a field. The original dump covered a single team, so
// whatever encodes the side had to be constant throughout it; that leaves only
// two Int32 candidates, and both are tried first. Failing those, any Int32 that
// splits the roster into exactly two stable groups is accepted.

#define TEAM_UNKNOWN ((size_t)-1)
static size_t g_teamOff = TEAM_UNKNOWN;
static bool   g_teamSearched = false;

static const size_t TEAM_HINTS[] = { 0x100, 0x118 };

/** Offsets of every Int32 field on the entity class, gathered once. */
static size_t g_intOff[64];
static int    g_intOffN = 0;

static void collect_int_fields() {
    if (g_intOffN || !R.playerCls) return;
    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(R.playerCls, &iter)) != nullptr && g_intOffN < 64) {
        if (g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC) continue;
        char *tn = g_il2.type_get_name(g_il2.field_get_type(f));
        if (!tn) continue;
        if (!strcmp(tn, "System.Int32")) g_intOff[g_intOffN++] = g_il2.field_get_offset(f);
        g_il2.free(tn);
    }
}

/** True when this offset splits the roster into exactly two values. */
static bool splits_roster(void **ents, int n, size_t off) {
    int32_t a = 0, b = 0;
    bool haveA = false, haveB = false;
    for (int i = 0; i < n; i++) {
        int32_t v = rd<int32_t>(ents[i], off);
        if (!haveA)          { a = v; haveA = true; }
        else if (v == a)     continue;
        else if (!haveB)     { b = v; haveB = true; }
        else if (v != b)     return false;      // a third value rules it out
    }
    return haveA && haveB;
}

static void find_team_field(void **ents, int n) {
    if (g_teamOff != TEAM_UNKNOWN || n < 3) return;
    collect_int_fields();

    for (size_t h = 0; h < sizeof(TEAM_HINTS) / sizeof(TEAM_HINTS[0]); h++)
        if (splits_roster(ents, n, TEAM_HINTS[h])) {
            g_teamOff = TEAM_HINTS[h];
            bplog("team: using field @0x%zx (from the single-team dump's constants)", g_teamOff);
            return;
        }

    for (int i = 0; i < g_intOffN; i++) {
        size_t off = g_intOff[i];
        if (off == R.oHp || off == R.oArm || off == R.oKill || off == R.oDeath ||
            off == R.oScore || off == R.oSlot) continue;
        if (splits_roster(ents, n, off)) {
            g_teamOff = off;
            bplog("team: using field @0x%zx (splits the roster in two)", g_teamOff);
            return;
        }
    }

    if (!g_teamSearched) {
        g_teamSearched = true;
        bplog("team: no field splits the roster yet, %d ints examined", g_intOffN);
    }
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

/** Signed shortest way round from a to b, in degrees, always within +-180.
 *  fmodf keeps the sign of its dividend, so the difference has to be pushed
 *  positive before it is folded — otherwise anything more than a turn apart
 *  comes back out of range, which is how a -401 degree offset got computed. */
static float angle_delta(float a, float b) {
    float d = fmodf(b - a + 180.0f, 360.0f);
    if (d < 0.0f) d += 360.0f;
    return d - 180.0f;
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

#define AIM_MAX_CAND 12000
// A live run reached 57 degrees of tracked yaw and still missed the lock because
// the sample count had not caught up — the travel gate is the strong evidence,
// the count only guards against a single lucky agreement.
#define AIM_LOCK_HITS 4
#define AIM_LOCK_YAW  40.0f
#define AIM_LOCK_PITCH 8.0f

struct AimCand {
    void  *obj;
    size_t off;
    float  prev;
    bool   havePrev;
    int8_t kYaw, kPitch;         // +1 or -1, 0 = undecided
    int8_t hitsYaw, hitsPitch;
    float  accYaw, accPitch;     // camera travel survived while still matching
    bool   dead;                 // failed the live write check, never offer again
};

static AimCand g_aimCand[AIM_MAX_CAND];
static int     g_aimCandN = 0;
static bool    g_aimCollected = false;

// A locked axis is known only by its sign: the field moves with the camera (+1)
// or against it (-1). No offset is kept, because the game's yaw turned out to be
// an unwrapped accumulator — it had run past -400 degrees — so there is no fixed
// constant relating it to a wrapped heading. Steering is therefore relative:
// read the field, add the change we want, write it back. That is immune to the
// accumulator, to whatever origin the field uses, and to wrapping.
static void  *g_yawObj, *g_pitchObj;
static size_t g_yawOff, g_pitchOff;
static int8_t g_yawK, g_pitchK;          // 0 = not locked
static bool   g_aimVerified = false;

static void aim_collect() {
    if (g_aimCollected) return;

    // Rate-limited on purpose: FindObjectsOfType is a Unity-locking call, and
    // retrying it from this thread at poll rate is a good way to wedge the game.
    static double lastTry = 0;
    if (now_s() - lastTry < 3.0) return;
    lastTry = now_s();

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

            // A view angle is rarely a lone float. Euler triplets and
            // quaternions are just as common, so each component of those is
            // watched as a candidate in its own right.
            int slots = 0;
            if (!strcmp(tn, "System.Single"))            slots = 1;
            else if (!strcmp(tn, "UnityEngine.Vector2")) slots = 2;
            else if (!strcmp(tn, "UnityEngine.Vector3")) slots = 3;
            else if (!strcmp(tn, "UnityEngine.Vector4") ||
                     !strcmp(tn, "UnityEngine.Quaternion")) slots = 4;

            size_t base = g_il2.field_get_offset(f);
            for (int s = 0; s < slots && g_aimCandN < AIM_MAX_CAND; s++) {
                AimCand &c = g_aimCand[g_aimCandN++];
                c.obj = objs[i];
                c.off = base + s * sizeof(float);
                c.prev = 0;
                c.havePrev = false;
                c.kYaw = c.kPitch = 0;
                c.hitsYaw = c.hitsPitch = 0;
                c.accYaw = c.accPitch = 0;
                c.dead = false;
            }
            g_il2.free(tn);
        }
    }
    g_aimCollected = true;
    bplog("aim: %d angle candidates under observation across %zu components", g_aimCandN, n);
}

/** Call once per tick with the camera's true angles. Locks on when a field has
 *  tracked them through enough distinct rotations to not be a coincidence. */
static void aim_correlate(float yaw, float pitch) {
    static float lastYaw = 0, lastPitch = 0;
    static bool  have = false;

    if (g_yawK && g_pitchK) return;
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
            c.havePrev = false;
            c.hitsYaw = c.hitsPitch = 0;
            c.accYaw = c.accPitch = 0;
            continue;
        }
        if (!c.havePrev) { c.prev = v; c.havePrev = true; continue; }

        float dv = angle_delta(c.prev, v);
        c.prev = v;

        if (!g_yawK && yawMoved) {
            // Tolerance scales with the size of the turn: a fixed window is
            // either too tight for a fast flick or too loose for a slow pan.
            float tol = 2.0f + 0.15f * fabsf(dYaw);
            int8_t k = 0;
            if (fabsf(dv - dYaw) < tol)      k = 1;
            else if (fabsf(dv + dYaw) < tol) k = -1;

            if (k && (c.kYaw == 0 || c.kYaw == k)) {
                c.kYaw = k;
                c.hitsYaw++;
                c.accYaw += fabsf(dYaw);
            } else {
                c.kYaw = 0; c.hitsYaw = 0; c.accYaw = 0;
            }

            if (c.hitsYaw >= AIM_LOCK_HITS && c.accYaw >= AIM_LOCK_YAW) {
                g_yawObj = c.obj; g_yawOff = c.off; g_yawK = c.kYaw;
                bplog("aim: yaw locked at %p+0x%zx k=%d (value %.1f) after %.0f deg over %d samples",
                      c.obj, c.off, c.kYaw, v, c.accYaw, c.hitsYaw);
            }
        }

        if (!g_pitchK && pitchMoved) {
            if (g_yawK && c.obj == g_yawObj && c.off == g_yawOff) continue;

            float tol = 1.0f + 0.15f * fabsf(dPitch);
            int8_t k = 0;
            if (fabsf(dv - dPitch) < tol)      k = 1;
            else if (fabsf(dv + dPitch) < tol) k = -1;

            if (k && (c.kPitch == 0 || c.kPitch == k)) {
                c.kPitch = k;
                c.hitsPitch++;
                c.accPitch += fabsf(dPitch);
            } else {
                c.kPitch = 0; c.hitsPitch = 0; c.accPitch = 0;
            }

            if (c.hitsPitch >= AIM_LOCK_HITS && c.accPitch >= AIM_LOCK_PITCH) {
                g_pitchObj = c.obj; g_pitchOff = c.off; g_pitchK = c.kPitch;
                bplog("aim: pitch locked at %p+0x%zx k=%d (value %.1f) after %.0f deg over %d samples",
                      c.obj, c.off, c.kPitch, v, c.accPitch, c.hitsPitch);
            }
        }
    }
}

/** Periodic note on how close the search is, so a silent failure is legible. */
static void aim_progress() {
    int yawAlive = 0, pitchAlive = 0;
    float bestYaw = 0, bestPitch = 0;
    for (int i = 0; i < g_aimCandN; i++) {
        if (g_aimCand[i].hitsYaw > 0)   { yawAlive++;   if (g_aimCand[i].accYaw > bestYaw) bestYaw = g_aimCand[i].accYaw; }
        if (g_aimCand[i].hitsPitch > 0) { pitchAlive++; if (g_aimCand[i].accPitch > bestPitch) bestPitch = g_aimCand[i].accPitch; }
    }
    bplog("aim: %d cand, yaw %d still matching (best %.0f/%.0f deg), "
          "pitch %d still matching (best %.0f/%.0f deg)",
          g_aimCandN, yawAlive, bestYaw, AIM_LOCK_YAW,
          pitchAlive, bestPitch, AIM_LOCK_PITCH);
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
    g_yawK = g_pitchK = 0;
    g_yawObj = g_pitchObj = nullptr;
    g_aimVerified = false;
}

static bool aim_ready() { return g_yawK && g_pitchK && g_aimVerified; }

/** Adds a change to each axis in place. Zero leaves an axis alone. */
static void aim_nudge(float dYaw, float dPitch) {
    if (g_yawK && dYaw != 0.0f && dYaw == dYaw) {
        float v = rd<float>(g_yawObj, g_yawOff) + g_yawK * dYaw;
        memcpy((uint8_t *)g_yawObj + g_yawOff, &v, sizeof(float));
    }
    if (g_pitchK && dPitch != 0.0f && dPitch == dPitch) {
        float v = rd<float>(g_pitchObj, g_pitchOff) + g_pitchK * dPitch;
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
    static int    leg = 0;            // 0 idle, 1 pushing +, 2 pushing -
    static float  target = 0, before = 0;
    static double at = 0;

    // Two legs in opposite directions. A single leg accepted on absolute
    // movement is not a test at all — the player turning by hand passes it, and
    // did: a probe asking for +8 was confirmed by the camera drifting -3.1.
    // Ordinary input will not follow a push one way and then the other on cue.
    if (leg == 0) {
        leg = 1;
        before = camYaw;
        target = camYaw + 12.0f;
        at = now_s();
    }

    float remaining = angle_delta(camYaw, target);
    aim_nudge(remaining, 0.0f);       // steer every tick; one write gets overwritten

    float moved = angle_delta(before, camYaw);
    float want  = (leg == 1) ? 12.0f : -12.0f;
    bool  arrived = (want > 0) ? (moved > 7.0f) : (moved < -7.0f);

    if (arrived) {
        if (leg == 1) {
            leg = 2;                  // now prove it comes back
            before = camYaw;
            target = camYaw - 12.0f;
            at = now_s();
            bplog("aim: probe leg 1 followed %.1f deg, reversing", moved);
        } else {
            leg = 0;
            g_aimVerified = true;
            bplog("aim: write confirmed, camera followed both directions");
        }
    } else if (now_s() - at > 1.2) {
        leg = 0;
        aim_forget("camera did not follow the probe");
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
    resolve_view();

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

        if (C.bones) {
            match_rigs(ents, n);
            // Only pay for the Unity-touching scan when something is unrigged,
            // and never more than once every few seconds.
            bool unrigged = false;
            for (int i = 0; i < n && !unrigged; i++) {
                Vec3 p = rd<Vec3>(ents[i], R.oPos);
                if (p.x == 0 && p.y == 0 && p.z == 0) continue;
                if (!rig_for(ents[i])) unrigged = true;
            }
            if (unrigged && now_s() - g_lastRendererScan > 6.0) {
                g_lastRendererScan = now_s();
                scan_renderers();
                match_rigs(ents, n);
            }
        } else {
            g_rigN = 0;
        }

        // Team. The local player is the entity whose position is never
        // replicated; friendlies are whoever shares its team value.
        int     localIdx = -1;
        int32_t ownTeam = 0;
        bool    haveTeam = false;
        for (int i = 0; i < n; i++) {
            Vec3 p = rd<Vec3>(ents[i], R.oPos);
            if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f) { localIdx = i; break; }
        }
        if (C.teamCheck) {
            find_team_field(ents, n);
            if (g_teamOff != TEAM_UNKNOWN && localIdx >= 0) {
                ownTeam = rd<int32_t>(ents[localIdx], g_teamOff);
                haveTeam = true;
            }
        }

        EntityView tmp[MAX_ENT];
        int out = 0;
        int skipZero = 0, skipProj = 0, skipTeam = 0, skipDead = 0;

        // best aimbot target this tick
        float bestAngle = 1e9f;
        Vec3  bestPoint{0, 0, 0};
        bool  haveTarget = false;
        float nearestAngle = 1e9f;      // ignores the FOV limit, for the trigger

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
                      "to=(%.2f,%.2f,%.2f) go=%p screen=(%.3f,%.3f)%s",
                      i, nm, rd<int32_t>(e, R.oHp), p.x, p.y, p.z,
                      nf.x, nf.y, nf.z, nt.x, nt.y, nt.z,
                      rd<void *>(e, OFF_GAMEOBJECT), sx, sy,
                      i == localIdx ? "  <- local" : "");

                // Every Int32 on the entity, so the team field can be picked out
                // of a log by eye if the automatic split does not find it.
                collect_int_fields();
                char ints[512];
                int w = 0;
                for (int k = 0; k < g_intOffN && w < (int)sizeof(ints) - 16; k++)
                    w += snprintf(ints + w, sizeof(ints) - w, "%zx=%d ",
                                  g_intOff[k], rd<int32_t>(e, g_intOff[k]));
                bplog("        ints %s", ints);
            }

            // Only one filter, and it is the one the data supports: an entity
            // with no replicated position cannot be drawn. That covers the local
            // player and anyone not yet spawned. NMAMove marks bots, not self,
            // so keying off it deleted every bot from the overlay.
            Vec3 p = rd<Vec3>(e, R.oPos);
            if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f) { skipZero++; continue; }
            if (haveTeam && rd<int32_t>(e, g_teamOff) == ownTeam) { skipTeam++; continue; }

            // A corpse keeps its last replicated position, so drawing it leaves
            // a marker sitting where someone no longer is. Dead entities never
            // enter the snapshot.
            int hp = rd<int32_t>(e, R.oHp);
            if (hp <= 0) { skipDead++; continue; }

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

            // Real bones when the model was matched, posed rig otherwise.
            Vec3 joints[JOINTS];
            Rig *rig = rig_for(e);
            Vec3 nf2 = rd<Vec3>(e, OFF_NETPOS_FROM), nt2 = rd<Vec3>(e, OFF_NETPOS_TO);
            float step = sqrtf((nt2.x - nf2.x) * (nt2.x - nf2.x) +
                               (nt2.z - nf2.z) * (nt2.z - nf2.z));
            build_joints(p, d, rd<float>(e, R.oDist), step * 12.0f, joints);
            if (rig)
                for (int k = 0; k < JOINTS; k++)
                    if (unity_alive(rig->bone[k])) joints[k] = bone_pos(rig->bone[k]);

            // Aimbot target selection. Head/chest/hip map onto the same rig the
            // skeleton uses; "nearest" takes whichever joint is closest to where
            // the view already points, which needs the least correction.
            if (hp > 0) {
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

                    if (ang < nearestAngle) nearestAngle = ang;
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
        float dYawWanted = 0, dPitchWanted = 0;

        if (C.rcs && havePrevPitch && g_viewReady) {
            // Recoil pushes the view up, which drives pitch negative. Only a
            // kick sharper than deliberate look-up input is fought, and only
            // downward, so ordinary aiming is left alone.
            float dp = camPitch - prevPitch;
            if (dp < -0.15f) dPitchWanted -= dp * (C.rcsPower / 100.0f);
        }

        if (C.aimbot && haveTarget && g_viewReady) {
            float dx = bestPoint.x - eye.x, dy = bestPoint.y - eye.y, dz = bestPoint.z - eye.z;
            float m = sqrtf(dx * dx + dy * dy + dz * dz);
            if (m > 0.01f) {
                Vec3 want{dx / m, dy / m, dz / m};
                float k = C.speed / 100.0f;
                if (k > 1) k = 1;
                if (k < 0.01f) k = 0.01f;
                dYawWanted   += angle_delta(camYaw, deg_yaw(want)) * k;
                dPitchWanted += (deg_pitch(want) - camPitch) * k;
            }
        }

        if (dYawWanted != 0 || dPitchWanted != 0) {
            static bool announced = false;
            bool ok = steer_view(cam, dYawWanted, dPitchWanted);
            if (!announced) { announced = true; bplog("view: first steer %s", ok ? "applied" : "FAILED"); }
        }

        // ---- what the overlay shows -----------------------------------------
        AimInfo info{};
        info.state = g_viewReady ? 2.0f : 0.0f;
        info.triggerHit = (C.trigger && nearestAngle <= C.triggerFov * 0.5f) ? 1.0f : 0.0f;
        if (haveTarget) {
            float tx, ty;
            if (world_to_screen(vp, bestPoint, &tx, &ty)) {
                info.hasTarget = 1.0f;
                info.targetX = tx;
                info.targetY = ty;
            }
        }
        // Screen radius of the aim cone: a point at half-angle a off the axis
        // lands at tan(a) * m11 in clip space, half of that in viewport terms.
        {
            float half = C.fov * 0.5f;
            if (half >= 89.0f) info.fovRadius = 10.0f;      // effectively the whole screen
            else info.fovRadius = tanf(half / 57.29578f) * proj.m[5] * 0.5f;
        }
        pthread_mutex_lock(&g_lock);
        g_aimInfo = info;
        pthread_mutex_unlock(&g_lock);
        prevPitch = camPitch;
        havePrevPitch = true;

        if (diag)
            bplog("entities=%d drawn=%d (skip zeroPos=%d team=%d dead=%d behind=%d) "
                  "eye=(%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f aim=%s target=%s",
                  n, out, skipZero, skipTeam, skipDead, skipProj, eye.x, eye.y, eye.z,
                  camYaw, camPitch,
                  g_viewReady ? "steering" : "view api unavailable",
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
Java_com_esp_Native_aimInfo(JNIEnv *env, jclass, jfloatArray out) {
    pthread_mutex_lock(&g_lock);
    AimInfo i = g_aimInfo;
    pthread_mutex_unlock(&g_lock);
    float v[6] = { i.hasTarget, i.targetX, i.targetY, i.fovRadius, i.state, i.triggerHit };
    env->SetFloatArrayRegion(out, 0, 6, v);
}

JNIEXPORT void JNICALL
Java_com_esp_Native_config(JNIEnv *, jclass, jboolean teamCheck, jboolean aimbot,
                           jfloat fov, jfloat speed, jint bone,
                           jboolean rcs, jfloat rcsPower, jboolean bones,
                           jboolean trigger, jfloat triggerFov) {
    pthread_mutex_lock(&g_cfgLock);
    g_cfg.trigger    = trigger;
    g_cfg.triggerFov = triggerFov;
    g_cfg.teamCheck = teamCheck;
    g_cfg.aimbot    = aimbot;
    g_cfg.fov       = fov;
    g_cfg.speed     = speed;
    g_cfg.bone      = bone;
    g_cfg.rcs       = rcs;
    g_cfg.rcsPower  = rcsPower;
    g_cfg.bones     = bones;
    pthread_mutex_unlock(&g_cfgLock);
}

JNIEXPORT jboolean JNICALL
Java_com_esp_Native_aimReady(JNIEnv *, jclass) { return g_viewReady; }

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
