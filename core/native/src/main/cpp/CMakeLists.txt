cmake_minimum_required(VERSION 3.22.1)
project(unique_native CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page alignment.
#
# Android 15 introduced devices with a 16 KB page size and Android 16 makes 16 KB
# alignment mandatory for apps targeting API 36. A library linked with the historical
# 4 KB max-page-size simply fails to map on such a device, with no useful diagnostic.
# Every native target in UNIQUE is linked with this, and `ElfInspector` in core/common
# checks imported third-party libraries for the same property at install time.
set(UNIQUE_LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

add_library(unique_native SHARED
        unique_native.cpp
        io_redirect.cpp
        plt_hook.cpp
        prop_virtual.cpp
        crash_handler.cpp
        vulkan_probe.cpp
)

target_compile_options(unique_native PRIVATE
        -Wall -Wextra -Werror=return-type
        -ffunction-sections -fdata-sections
        -fvisibility=hidden
)

set_target_properties(unique_native PROPERTIES
        LINK_FLAGS "${UNIQUE_LINK_FLAGS} -Wl,--gc-sections"
)

find_library(log-lib log)
target_link_libraries(unique_native ${log-lib})
