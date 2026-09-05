// What Vulkan this *device* has, asked from UNIQUE's own process.
//
// The point is comparison. `t28` establishes whether a guest can bring Vulkan up; on its
// own that answer is ambiguous, because a device with no working Vulkan produces the same
// failure as a virtualization layer that broke it. Running the identical sequence outside
// virtualization is what separates the two — the same trick `t30` uses for WebView, and
// the same reason: a compatibility matrix must never record a device's limitation against
// UNIQUE.
//
// Deliberately a duplicate of the probe app's `probe_vulkan.c` rather than a shared
// library. The two must be able to disagree: if they ever diverge, the comparison is worth
// nothing, and a shared implementation would hide exactly the case worth catching — the
// probe being wrong in both places at once.

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

#include <vulkan/vulkan.h>

#include "unique_native.h"

namespace {

constexpr size_t kReportMax = 2048;

struct Report {
    char text[kReportMax];
    size_t used;
};

void add(Report* r, const char* key, const char* value) {
    int n = snprintf(r->text + r->used, kReportMax - r->used, "%s=%s\n", key, value);
    if (n > 0 && static_cast<size_t>(n) < kReportMax - r->used) r->used += static_cast<size_t>(n);
}

void add_u32(Report* r, const char* key, uint32_t value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%u", value);
    add(r, key, buf);
}

void add_bool(Report* r, const char* key, bool value) {
    add(r, key, value ? "true" : "false");
}

struct Api {
    PFN_vkCreateInstance createInstance;
    PFN_vkDestroyInstance destroyInstance;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getQueueFamilyProperties;
    PFN_vkCreateDevice createDevice;
    PFN_vkDestroyDevice destroyDevice;
    PFN_vkGetDeviceQueue getDeviceQueue;
};

bool resolve(void* lib, Api* api, Report* r) {
#define LOAD(field, name)                            \
    api->field = (PFN_##name) dlsym(lib, #name);     \
    if (api->field == nullptr) {                     \
        add(r, "missingSymbol", #name);              \
        return false;                                \
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
    return true;
}

const char* device_type_name(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "cpu";
        default:                                     return "other";
    }
}

/// Creates a logical device with one graphics queue and fetches it.
///
/// The queue handle is checked for null: a driver that returns VK_SUCCESS and hands back
/// nothing is a real failure mode, and is the one an "it initialised fine" check misses.
bool create_device_and_queue(const Api& api, VkPhysicalDevice gpu, Report* r) {
    uint32_t familyCount = 0;
    api.getQueueFamilyProperties(gpu, &familyCount, nullptr);
    if (familyCount == 0) {
        add(r, "queueFamilies", "0");
        return false;
    }
    if (familyCount > 16) familyCount = 16;
    VkQueueFamilyProperties families[16];
    api.getQueueFamilyProperties(gpu, &familyCount, families);
    add_u32(r, "queueFamilies", familyCount);

    uint32_t graphics = UINT32_MAX;
    for (uint32_t i = 0; i < familyCount; i++) {
        if (families[i].queueCount > 0 &&
            (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
            graphics = i;
            break;
        }
    }
    if (graphics == UINT32_MAX) {
        add(r, "graphicsQueueFamily", "none");
        return false;
    }
    add_u32(r, "graphicsQueueFamily", graphics);

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = graphics;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    VkDevice device = VK_NULL_HANDLE;
    VkResult rc = api.createDevice(gpu, &deviceInfo, nullptr, &device);
    add_u32(r, "createDeviceResult", static_cast<uint32_t>(rc));
    if (rc != VK_SUCCESS || device == VK_NULL_HANDLE) return false;
    add_bool(r, "deviceCreated", true);

    VkQueue queue = VK_NULL_HANDLE;
    api.getDeviceQueue(device, graphics, 0, &queue);
    const bool gotQueue = queue != VK_NULL_HANDLE;
    add_bool(r, "queueAcquired", gotQueue);

    api.destroyDevice(device, nullptr);
    return gotQueue;
}

}  // namespace

UNIQUE_EXPORT JNIEXPORT jstring JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeProbeVulkan(JNIEnv* env, jclass) {
    Report r{};
    r.used = 0;
    r.text[0] = '\0';
    add_bool(&r, "ran", true);

    // dlopen rather than a link dependency: a device with no Vulkan must still load
    // libunique_native and report that, instead of failing at load time with nothing to say.
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    add_bool(&r, "libraryLoaded", lib != nullptr);
    if (lib == nullptr) {
        const char* err = dlerror();
        add(&r, "dlerror", err != nullptr ? err : "(none)");
        return env->NewStringUTF(r.text);
    }

    Api api{};
    if (!resolve(lib, &api, &r)) {
        dlclose(lib);
        return env->NewStringUTF(r.text);
    }
    add_bool(&r, "symbolsResolved", true);

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "unique";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "unique";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult rc = api.createInstance(&instanceInfo, nullptr, &instance);
    add_u32(&r, "createInstanceResult", static_cast<uint32_t>(rc));
    if (rc != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        dlclose(lib);
        return env->NewStringUTF(r.text);
    }
    add_bool(&r, "instanceCreated", true);

    uint32_t gpuCount = 0;
    rc = api.enumeratePhysicalDevices(instance, &gpuCount, nullptr);
    add_u32(&r, "enumerateResult", static_cast<uint32_t>(rc));
    add_u32(&r, "physicalDevices", gpuCount);

    if (rc == VK_SUCCESS && gpuCount > 0) {
        if (gpuCount > 4) gpuCount = 4;
        VkPhysicalDevice gpus[4];
        rc = api.enumeratePhysicalDevices(instance, &gpuCount, gpus);
        if (rc == VK_SUCCESS || rc == VK_INCOMPLETE) {
            VkPhysicalDeviceProperties props;
            api.getPhysicalDeviceProperties(gpus[0], &props);
            add(&r, "deviceName", props.deviceName);
            add(&r, "deviceType", device_type_name(props.deviceType));
            add_u32(&r, "apiVersionMajor", VK_VERSION_MAJOR(props.apiVersion));
            add_u32(&r, "apiVersionMinor", VK_VERSION_MINOR(props.apiVersion));
            add_u32(&r, "driverVersion", props.driverVersion);
            add_u32(&r, "vendorId", props.vendorID);
            create_device_and_queue(api, gpus[0], &r);
        }
    }

    api.destroyInstance(instance, nullptr);
    add_bool(&r, "instanceDestroyed", true);
    dlclose(lib);
    return env->NewStringUTF(r.text);
}
