// BLOCKPOST ESP — native side.
//
// Runs one attached poller thread that walks the il2cpp heap, resolves the
// player container, projects every entity to screen space and publishes a
// snapshot. The Java overlay only reads the snapshot and paints it, so nothing
// managed is ever touched from the UI thread.

#include "il2cpp.h"
#include "offsets.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cmath>
#include <cstdlib>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "bpesp", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "bpesp", __VA_ARGS__)

#define MAX_ENT 64
#define STRIDE  14

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

static int  g_state = 0;               // 0 wait il2cpp, 1 scanning, 2 live, 3 no container
static char g_status[192] = "starting";
static float g_screenW = 0, g_screenH = 0;

static void set_status(int st, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char tmp[192];
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_lock);
    g_state = st;
    snprintf(g_status, sizeof(g_status), "%s", tmp);
    pthread_mutex_unlock(&g_lock);
    LOGI("%s", tmp);
}

// ---------------------------------------------------------------------------
// resolved game types
// ---------------------------------------------------------------------------
struct Resolved {
    void *image      = nullptr;   // Assembly-CSharp
    void *playerCls  = nullptr;
    char  playerName[64] = {0};

    // where the entity list lives
    void  *ownerStaticData = nullptr;  // static field block of the holder class
    size_t rootOff  = 0;               // offset of the root pointer inside it
    size_t chainOff = 0;               // second hop, or NO_HOP
    int    kind     = 0;               // 1 = T[], 2 = List<T>

    // field offsets (name-resolved, hardcoded fallback)
    size_t oSlot = OFF_SLOT,   oName  = OFF_NAME,   oHp    = OFF_HEALTH;
    size_t oArm  = OFF_ARMOR,  oKill  = OFF_KILLS,  oDeath = OFF_DEATHS;
    size_t oPos  = OFF_POSITION, oDir = OFF_LOOK_DIR, oMove = OFF_MOVE;
    size_t oScore= OFF_SCORE,  oDist  = OFF_DIST_LIFE, oNoTf = OFF_NO_TRANSFORM;
    size_t oPitch= OFF_PITCH,  oMoney = OFF_MONEY,  oLevel = OFF_LEVEL;

    // camera
    void *camCls = nullptr, *mGetMain = nullptr, *mView = nullptr;
    void *mProj = nullptr, *mPixW = nullptr, *mPixH = nullptr;
    void *camObj = nullptr;
};
static Resolved R;

#define NO_HOP ((size_t)-1)

// ---------------------------------------------------------------------------
// small helpers
// ---------------------------------------------------------------------------
static void *unbox(void *boxed) { return boxed ? (uint8_t *)boxed + 0x10 : nullptr; }

static void *invoke0(void *method, void *obj) {
    if (!method) return nullptr;
    void *exc = nullptr;
    void *r = g_il2.runtime_invoke(method, obj, nullptr, &exc);
    return exc ? nullptr : r;
}

// Field lookup by obfuscated name; leaves the fallback in place when absent.
static void resolve_field(void *cls, const char *name, size_t *out) {
    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
        const char *n = g_il2.field_get_name(f);
        if (n && strcmp(n, name) == 0) { *out = g_il2.field_get_offset(f); return; }
    }
}

// ---------------------------------------------------------------------------
// class discovery
// ---------------------------------------------------------------------------
// The entity class is a plain managed object (first field at 0x10, so it does
// not derive from UnityEngine.Object). Match on layout rather than on the
// obfuscated name, which rotates on every build.
static bool looks_like_player(void *cls) {
    if (!cls) return false;
    if (g_il2.class_instance_size(cls) < PLAYER_MIN_SIZE) return false;

    int hitName = 0, hitInt = 0, hitVec = 0, named = 0;
    void *iter = nullptr, *f;
    while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
        if (g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC) continue;
        size_t off = g_il2.field_get_offset(f);
        char *tn = g_il2.type_get_name(g_il2.field_get_type(f));
        if (!tn) continue;

        if ((off == OFF_NAME || off == OFF_DISPLAY_NAME) && !strcmp(tn, "System.String")) hitName++;
        if (!strcmp(tn, "System.Int32") &&
            (off == OFF_HEALTH || off == OFF_ARMOR || off == OFF_KILLS ||
             off == OFF_DEATHS || off == OFF_MONEY || off == OFF_SCORE)) hitInt++;
        if (!strcmp(tn, "UnityEngine.Vector3") &&
            (off == OFF_POSITION || off == OFF_LOOK_DIR ||
             off == OFF_NETPOS_FROM || off == OFF_NETPOS_TO)) hitVec++;

        const char *fn = g_il2.field_get_name(f);
        if (fn && (!strcmp(fn, NAME_POSITION) || !strcmp(fn, NAME_HEALTH) ||
                   !strcmp(fn, NAME_NAME)     || !strcmp(fn, NAME_MOVE))) named++;

        g_il2.free(tn);
    }
    // Either the exact dumped names survived, or the shape alone is conclusive.
    return named >= 3 || (hitName == 2 && hitInt >= 5 && hitVec >= 3);
}

static bool find_player_class() {
    static const char *assemblies[] = {
        "Assembly-CSharp", "Assembly-CSharp-firstpass", "Assembly-CSharp.dll",
        "Main", "Game", nullptr
    };
    void *domain = g_il2.domain_get();

    for (int a = 0; assemblies[a]; a++) {
        void *asm_ = g_il2.domain_assembly_open(domain, assemblies[a]);
        if (!asm_) continue;
        void *img = g_il2.assembly_get_image(asm_);
        if (!img) continue;

        size_t n = g_il2.image_get_class_count(img);
        LOGI("scanning %s: %zu classes", assemblies[a], n);
        for (size_t i = 0; i < n; i++) {
            void *cls = (void *)g_il2.image_get_class(img, i);
            if (!looks_like_player(cls)) continue;

            R.image = img;
            R.playerCls = cls;
            const char *cn = g_il2.class_get_name(cls);
            snprintf(R.playerName, sizeof(R.playerName), "%s", cn ? cn : "?");

            resolve_field(cls, NAME_SLOT,        &R.oSlot);
            resolve_field(cls, NAME_NAME,        &R.oName);
            resolve_field(cls, NAME_HEALTH,      &R.oHp);
            resolve_field(cls, NAME_ARMOR,       &R.oArm);
            resolve_field(cls, NAME_KILLS,       &R.oKill);
            resolve_field(cls, NAME_DEATHS,      &R.oDeath);
            resolve_field(cls, NAME_POSITION,    &R.oPos);
            resolve_field(cls, NAME_LOOK_DIR,    &R.oDir);
            resolve_field(cls, NAME_MOVE,        &R.oMove);
            resolve_field(cls, NAME_SCORE,       &R.oScore);
            resolve_field(cls, NAME_DIST_LIFE,   &R.oDist);
            resolve_field(cls, NAME_NO_TRANSFORM,&R.oNoTf);
            resolve_field(cls, NAME_PITCH,       &R.oPitch);
            resolve_field(cls, NAME_MONEY,       &R.oMoney);
            resolve_field(cls, NAME_LEVEL,       &R.oLevel);

            LOGI("entity class '%s' size=0x%x  hp@0x%zx pos@0x%zx dir@0x%zx",
                 R.playerName, g_il2.class_instance_size(cls), R.oHp, R.oPos, R.oDir);
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// container discovery
// ---------------------------------------------------------------------------
// Classifies a field type against the entity class: 1 = T[], 2 = List<T>.
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

static bool find_container() {
    size_t n = g_il2.image_get_class_count(R.image);

    // Pass 1 — a static field that is itself the container.
    for (size_t i = 0; i < n; i++) {
        void *cls = (void *)g_il2.image_get_class(R.image, i);
        if (!cls) continue;
        void *sd = g_il2.class_get_static_field_data(cls);
        if (!sd) continue;

        void *iter = nullptr, *f;
        while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
            if (!(g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC)) continue;
            int k = container_kind(g_il2.field_get_type(f));
            if (!k) continue;
            R.ownerStaticData = sd;
            R.rootOff  = g_il2.field_get_offset(f);
            R.chainOff = NO_HOP;
            R.kind     = k;
            LOGI("container: static %s.%s kind=%d",
                 g_il2.class_get_name(cls), g_il2.field_get_name(f), k);
            return true;
        }
    }

    // Pass 2 — singleton pattern: static Manager instance; instance.list.
    for (size_t i = 0; i < n; i++) {
        void *cls = (void *)g_il2.image_get_class(R.image, i);
        if (!cls) continue;
        void *sd = g_il2.class_get_static_field_data(cls);
        if (!sd) continue;

        void *iter = nullptr, *f;
        while ((f = g_il2.class_get_fields(cls, &iter)) != nullptr) {
            if (!(g_il2.field_get_flags(f) & FIELD_ATTRIBUTE_STATIC)) continue;
            void *ft = g_il2.field_get_type(f);
            void *fc = g_il2.class_from_type(ft);
            if (!fc || g_il2.class_is_valuetype(fc)) continue;

            size_t sOff = g_il2.field_get_offset(f);
            void *inst = rd<void *>(sd, sOff);
            if (!inst) continue;

            void *it2 = nullptr, *f2;
            while ((f2 = g_il2.class_get_fields(fc, &it2)) != nullptr) {
                if (g_il2.field_get_flags(f2) & FIELD_ATTRIBUTE_STATIC) continue;
                int k = container_kind(g_il2.field_get_type(f2));
                if (!k) continue;
                R.ownerStaticData = sd;
                R.rootOff  = sOff;
                R.chainOff = g_il2.field_get_offset(f2);
                R.kind     = k;
                LOGI("container: %s.%s -> .%s kind=%d",
                     g_il2.class_get_name(cls), g_il2.field_get_name(f),
                     g_il2.field_get_name(f2), k);
                return true;
            }
        }
    }
    return false;
}

// Resolves the container to (elements base, count) for the current frame.
static int read_entities(void **out, int cap) {
    if (!R.ownerStaticData) return 0;

    void *root = rd<void *>(R.ownerStaticData, R.rootOff);
    if (!root) return 0;
    if (R.chainOff != NO_HOP) {
        root = rd<void *>(root, R.chainOff);
        if (!root) return 0;
    }

    void *arr = root;
    int   count;
    if (R.kind == 2) {                                  // List<T>
        arr   = rd<void *>(root, LIST_ITEMS_OFF);
        count = rd<int32_t>(root, LIST_SIZE_OFF);
        if (!arr) return 0;
    } else {                                            // T[]
        count = (int)rd<uint64_t>(root, 0x18);
    }
    if (count < 0 || count > 512) return 0;

    int backing = (int)rd<uint64_t>(arr, 0x18);
    if (count > backing) count = backing;
    if (count > cap) count = cap;

    void **data = (void **)IL2CPP_ARRAY_DATA(arr);
    int got = 0;
    for (int i = 0; i < count; i++)
        if (data[i]) out[got++] = data[i];
    return got;
}

// ---------------------------------------------------------------------------
// camera
// ---------------------------------------------------------------------------
static bool resolve_camera() {
    void *domain = g_il2.domain_get();
    static const char *mods[] = { "UnityEngine.CoreModule", "UnityEngine", nullptr };

    for (int i = 0; mods[i]; i++) {
        void *asm_ = g_il2.domain_assembly_open(domain, mods[i]);
        if (!asm_) continue;
        void *img = g_il2.assembly_get_image(asm_);
        if (!img) continue;
        void *cls = g_il2.class_from_name(img, "UnityEngine", "Camera");
        if (!cls) continue;

        R.camCls   = cls;
        R.mGetMain = g_il2.class_get_method_from_name(cls, "get_main", 0);
        R.mView    = g_il2.class_get_method_from_name(cls, "get_worldToCameraMatrix", 0);
        R.mProj    = g_il2.class_get_method_from_name(cls, "get_projectionMatrix", 0);
        R.mPixW    = g_il2.class_get_method_from_name(cls, "get_pixelWidth", 0);
        R.mPixH    = g_il2.class_get_method_from_name(cls, "get_pixelHeight", 0);
        return R.mGetMain && R.mView && R.mProj;
    }
    return false;
}

// A destroyed Camera keeps a live managed shell with a null m_CachedPtr.
static void *camera_object() {
    if (R.camObj && rd<void *>(R.camObj, 0x10) != nullptr) return R.camObj;
    R.camObj = invoke0(R.mGetMain, nullptr);
    return R.camObj;
}

static Mat4 mat_mul(const Mat4 &a, const Mat4 &b) {   // column-major
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
    if (cw < 0.01f) return false;   // behind the near plane
    *sx = (cx / cw * 0.5f + 0.5f) * sw;
    *sy = (1.0f - (cy / cw * 0.5f + 0.5f)) * sh;
    return true;
}

// ---------------------------------------------------------------------------
// poller
// ---------------------------------------------------------------------------
static void *poll_thread(void *) {
    void *h = nullptr;
    for (int i = 0; i < 600 && !h; i++) {          // libil2cpp lands a few s in
        h = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (!h) usleep(200 * 1000);
    }
    if (!h) { set_status(0, "libil2cpp.so never loaded"); return nullptr; }
    if (!il2cpp_bind(h)) { set_status(0, "il2cpp api bind failed"); return nullptr; }

    // Give the runtime time to build its class tables before walking them.
    sleep(4);
    g_il2.thread_attach(g_il2.domain_get());

    set_status(1, "scanning for entity class");
    while (!find_player_class()) { sleep(2); }
    set_status(1, "entity class %s", R.playerName);

    if (!resolve_camera()) set_status(1, "camera api unresolved");

    void *ents[MAX_ENT];

    for (;;) {
        if (!R.ownerStaticData) {
            if (find_container()) set_status(2, "live (%s)", R.playerName);
            else { set_status(3, "no entity list yet — join a match"); sleep(2); continue; }
        }

        int n = read_entities(ents, MAX_ENT);
        if (n == 0) { usleep(100 * 1000); continue; }

        void *cam = camera_object();
        if (!cam) { usleep(100 * 1000); continue; }

        void *vBox = invoke0(R.mView, cam);
        void *pBox = invoke0(R.mProj, cam);
        if (!vBox || !pBox) { usleep(100 * 1000); continue; }

        Mat4 view = rd<Mat4>(unbox(vBox), 0);
        Mat4 proj = rd<Mat4>(unbox(pBox), 0);
        Mat4 vp   = mat_mul(proj, view);

        float sw = g_screenW, sh = g_screenH;
        if (R.mPixW && R.mPixH) {
            void *bw = invoke0(R.mPixW, cam), *bh = invoke0(R.mPixH, cam);
            if (bw && bh) {
                float w = (float)rd<int32_t>(unbox(bw), 0);
                float h = (float)rd<int32_t>(unbox(bh), 0);
                if (w > 1 && h > 1) { sw = w; sh = h; }
            }
        }
        if (sw < 1 || sh < 1) { usleep(100 * 1000); continue; }

        // Local player: the only entity whose movement component is populated.
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

        for (int i = 0; i < n && out < MAX_ENT; i++) {
            void *e = ents[i];
            bool local = rd<void *>(e, R.oMove) != nullptr;
            if (local) continue;
            if (rd<uint8_t>(e, R.oNoTf)) continue;          // position not replicated

            Vec3 p = rd<Vec3>(e, R.oPos);
            if (p.x == 0.0f && p.y == 0.0f && p.z == 0.0f) continue;

            int hp = rd<int32_t>(e, R.oHp);
            Vec3 head{p.x, p.y + PLAYER_HEIGHT, p.z};

            float fx, fy, hx, hy;
            if (!world_to_screen(vp, p, sw, sh, &fx, &fy)) continue;
            if (!world_to_screen(vp, head, sw, sh, &hx, &hy)) continue;

            Vec3 d = rd<Vec3>(e, R.oDir);
            Vec3 tip{p.x + d.x * 2.0f, p.y + PLAYER_HEIGHT * 0.6f + d.y * 2.0f,
                     p.z + d.z * 2.0f};
            float tx = fx, ty = fy;
            world_to_screen(vp, tip, sw, sh, &tx, &ty);

            float dist = 0;
            if (haveEye) {
                float dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
                dist = sqrtf(dx * dx + dy * dy + dz * dz);
            }

            float boxH = fabsf(fy - hy);
            EntityView &v = tmp[out];
            v.data[0]  = fx;
            v.data[1]  = fy;
            v.data[2]  = hx;
            v.data[3]  = hy;
            v.data[4]  = boxH * PLAYER_WIDTH_RATIO;         // box width
            v.data[5]  = (float)hp;
            v.data[6]  = (float)rd<int32_t>(e, R.oArm);
            v.data[7]  = dist;
            v.data[8]  = (float)rd<int32_t>(e, R.oKill);
            v.data[9]  = (float)rd<int32_t>(e, R.oDeath);
            v.data[10] = (float)rd<int32_t>(e, R.oScore);
            v.data[11] = hp > 0 ? 1.0f : 0.0f;              // alive
            v.data[12] = tx;
            v.data[13] = ty;
            il2cpp_string_to_utf8(rd<void *>(e, R.oName), v.name, sizeof(v.name));
            out++;
        }

        pthread_mutex_lock(&g_lock);
        memcpy(g_ent, tmp, sizeof(EntityView) * out);
        g_count = out;
        if (g_state != 2) { g_state = 2; snprintf(g_status, sizeof(g_status), "live"); }
        pthread_mutex_unlock(&g_lock);

        usleep(8 * 1000);        // ~120 Hz ceiling; the overlay paces itself
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT void JNICALL
Java_com_esp_Native_start(JNIEnv *, jclass, jint w, jint h) {
    static bool started = false;
    g_screenW = (float)w;
    g_screenH = (float)h;
    if (started) return;
    started = true;
    pthread_t t;
    pthread_create(&t, nullptr, poll_thread, nullptr);
    pthread_detach(t);
    LOGI("poller started (%dx%d)", w, h);
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
    pthread_mutex_lock(&g_lock);
    char buf[192];
    snprintf(buf, sizeof(buf), "%s", g_status);
    pthread_mutex_unlock(&g_lock);
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_esp_Native_fetch(JNIEnv *env, jclass, jfloatArray outData, jobjectArray outNames) {
    pthread_mutex_lock(&g_lock);
    int n = g_count;
    if (n > MAX_ENT) n = MAX_ENT;
    static float flat[MAX_ENT * STRIDE];
    static char  names[MAX_ENT][40];
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
