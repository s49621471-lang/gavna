// Vulkan, from inside a virtualized process.
//
// Deliberately does more than ask whether the library exists. A "Vulkan works" claim that
// rests on dlopen() succeeding is worth nothing: libvulkan.so is present on essentially
// every Android 10+ device, including ones whose loader finds no ICD at all and whose
// vkEnumeratePhysicalDevices returns zero. So this creates a real instance, enumerates
// real physical devices, and creates a real logical device with a real graphics queue -
// the first point at which the driver has actually committed to anything.
//
// libvulkan is opened with dlopen rather than linked, for two reasons. A device with no
// Vulkan at all must still be able to load this library and report that fact, rather than
// failing at load time with nothing to say. And going through dlopen is what exercises
// UNIQUE's own library-load path: the loader has to find a *system* library while the
// process is running under redirected IO, and a redirect scope that was too wide would
// show up here as a driver that cannot open its own /system files.

#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include <vulkan/vulkan.h>

#define REPORT_MAX 4096

typedef struct {
    char text[REPORT_MAX];
    size_t used;
} report_t;

static void report_add(report_t* r, const char* key, const char* value) {
    int n = snprintf(r->text + r->used, REPORT_MAX - r->used, "%s=%s\n", key, value);
    if (n > 0 && (size_t) n < REPORT_MAX - r->used) r->used += (size_t) n;
}

static void report_add_u32(report_t* r, const char* key, uint32_t value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%u", value);
    report_add(r, key, buf);
}

static void report_add_bool(report_t* r, const char* key, int value) {
    report_add(r, key, value ? "true" : "false");
}

// Only the entry points this probe actually calls. Resolving each one by name is also how
// a partially-stubbed loader is caught: a library that exports vkCreateInstance and
// nothing else is a thing that exists.
typedef struct {
    PFN_vkCreateInstance createInstance;
    PFN_vkDestroyInstance destroyInstance;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getQueueFamilyProperties;
    PFN_vkCreateDevice createDevice;
    PFN_vkDestroyDevice destroyDevice;
    PFN_vkGetDeviceQueue getDeviceQueue;
} vk_api;

static int resolve(void* lib, vk_api* api, report_t* r) {
#define LOAD(field, name)                                    \
    api->field = (PFN_##name) dlsym(lib, #name);             \
    if (api->field == NULL) {                                \
        report_add(r, "missingSymbol", #name);               \
        return 0;                                            \
    }
    LOAD(createInstance, vkCreateInstance)
    LOAD(destroyInstance, vkDestroyInstance)
    LOAD(enumeratePhysicalDevices, vkEnumeratePhysicalDevices)
    LOAD(getPhysicalDeviceProperties, vkGetPhysicalDeviceProperties)
    LOAD(getQueueFamilyProperties, vkGetPhysicalDeviceQueueFamilyProperties)
    LOAD(createDevice, vkCreateDevice)
    LOAD(destroyDevice, vkDestroyDevice)
    LOAD(getDeviceQueue, vkGetDeviceQueue)
#undef LOAD
    return 1;
}

static const char* device_type_name(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "cpu";
        default:                                     return "other";
    }
}

// Creates a logical device with one graphics queue on `gpu`, and fetches the queue.
// Returns 1 only when the queue handle really came back non-null: a driver that returns
// VK_SUCCESS and hands back nothing is exactly the shape of failure this probe exists for.
static int create_device_and_queue(const vk_api* api, VkPhysicalDevice gpu, report_t* r) {
    uint32_t familyCount = 0;
    api->getQueueFamilyProperties(gpu, &familyCount, NULL);
    if (familyCount == 0) {
        report_add(r, "queueFamilies", "0");
        return 0;
    }
    if (familyCount > 16) familyCount = 16;
    VkQueueFamilyProperties families[16];
    api->getQueueFamilyProperties(gpu, &familyCount, families);
    report_add_u32(r, "queueFamilies", familyCount);

    uint32_t graphicsFamily = UINT32_MAX;
    for (uint32_t i = 0; i < familyCount; i++) {
        if (families[i].queueCount > 0 &&
            (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
            graphicsFamily = i;
            break;
        }
    }
    if (graphicsFamily == UINT32_MAX) {
        report_add(r, "graphicsQueueFamily", "none");
        return 0;
    }
    report_add_u32(r, "graphicsQueueFamily", graphicsFamily);

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo;
    memset(&queueInfo, 0, sizeof(queueInfo));
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = graphicsFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo;
    memset(&deviceInfo, 0, sizeof(deviceInfo));
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    VkDevice device = VK_NULL_HANDLE;
    VkResult rc = api->createDevice(gpu, &deviceInfo, NULL, &device);
    report_add_u32(r, "createDeviceResult", (uint32_t) rc);
    if (rc != VK_SUCCESS || device == VK_NULL_HANDLE) return 0;
    report_add_bool(r, "deviceCreated", 1);

    VkQueue queue = VK_NULL_HANDLE;
    api->getDeviceQueue(device, graphicsFamily, 0, &queue);
    int gotQueue = queue != VK_NULL_HANDLE;
    report_add_bool(r, "queueAcquired", gotQueue);

    api->destroyDevice(device, NULL);
    report_add_bool(r, "deviceDestroyed", 1);
    return gotQueue;
}

JNIEXPORT jstring JNICALL
Java_com_unique_probe_ProbeVulkan_probe(JNIEnv* env, jclass clazz) {
    (void) clazz;
    report_t r;
    r.used = 0;
    r.text[0] = '\0';

    report_add_bool(&r, "ran", 1);

    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    report_add_bool(&r, "libraryLoaded", lib != NULL);
    if (lib == NULL) {
        const char* err = dlerror();
        report_add(&r, "dlerror", err != NULL ? err : "(none)");
        return (*env)->NewStringUTF(env, r.text);
    }

    vk_api api;
    memset(&api, 0, sizeof(api));
    if (!resolve(lib, &api, &r)) {
        dlclose(lib);
        return (*env)->NewStringUTF(env, r.text);
    }
    report_add_bool(&r, "symbolsResolved", 1);

    VkApplicationInfo appInfo;
    memset(&appInfo, 0, sizeof(appInfo));
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "unique-probe";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "unique-probe";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo;
    memset(&instanceInfo, 0, sizeof(instanceInfo));
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult rc = api.createInstance(&instanceInfo, NULL, &instance);
    report_add_u32(&r, "createInstanceResult", (uint32_t) rc);
    if (rc != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        dlclose(lib);
        return (*env)->NewStringUTF(env, r.text);
    }
    report_add_bool(&r, "instanceCreated", 1);

    uint32_t gpuCount = 0;
    rc = api.enumeratePhysicalDevices(instance, &gpuCount, NULL);
    report_add_u32(&r, "enumerateResult", (uint32_t) rc);
    report_add_u32(&r, "physicalDevices", gpuCount);

    if (rc == VK_SUCCESS && gpuCount > 0) {
        if (gpuCount > 4) gpuCount = 4;
        VkPhysicalDevice gpus[4];
        rc = api.enumeratePhysicalDevices(instance, &gpuCount, gpus);
        if (rc == VK_SUCCESS || rc == VK_INCOMPLETE) {
            VkPhysicalDeviceProperties props;
            api.getPhysicalDeviceProperties(gpus[0], &props);
            report_add(&r, "deviceName", props.deviceName);
            report_add(&r, "deviceType", device_type_name(props.deviceType));
            report_add_u32(&r, "apiVersionMajor", VK_VERSION_MAJOR(props.apiVersion));
            report_add_u32(&r, "apiVersionMinor", VK_VERSION_MINOR(props.apiVersion));
            report_add_u32(&r, "driverVersion", props.driverVersion);
            report_add_u32(&r, "vendorId", props.vendorID);
            create_device_and_queue(&api, gpus[0], &r);
        }
    }

    api.destroyInstance(instance, NULL);
    report_add_bool(&r, "instanceDestroyed", 1);
    dlclose(lib);
    return (*env)->NewStringUTF(env, r.text);
}
