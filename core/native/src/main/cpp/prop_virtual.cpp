#include <map>
#include <mutex>
#include <string>

#include "unique_native.h"

namespace unique::prop_virtual {
namespace {

std::map<std::string, std::string> g_props;
std::mutex g_mutex;
bool g_installed = false;

}  // namespace

void set_property(const char* key, const char* value) {
    if (key == nullptr || value == nullptr) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_props[key] = value;
}

void clear_properties() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_props.clear();
}

int property_count() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<int>(g_props.size());
}

const char* lookup(const char* key) {
    if (key == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_props.find(key);
    return it == g_props.end() ? nullptr : it->second.c_str();
}

bool installed() { return g_installed; }

// TODO(phase-6): intercept __system_property_get / __system_property_find /
// __system_property_read_callback so that Build.* overrides from a DeviceProfile are
// consistent for native code as well as Java.
//
// The property *store* above is complete; the interception is not. Until it lands, a
// DeviceProfile's Build overrides apply to Java callers only (core/vprofile), and
// DeviceProfileProvider reports that asymmetry in Diagnostics rather than letting a
// virtual app see two different values for the same property depending on which layer
// asked. Reporting one value in Java and another in native is exactly the incoherence
// this design exists to prevent, so the Java-side overrides stay off by default until
// this is wired up.
InstallStatus install() {
    ULOGW("prop_virtual::install() is not implemented yet (see TODO(phase-6)); "
          "%d override(s) staged", property_count());
    g_installed = false;
    return InstallStatus::kNotImplemented;
}

}  // namespace unique::prop_virtual
