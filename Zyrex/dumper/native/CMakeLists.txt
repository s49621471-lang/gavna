cmake_minimum_required(VERSION 3.22)
project(zyrexdump CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zyrexdump SHARED
    src/main.cpp
    src/il2cpp_api.cpp
    src/dumper.cpp
    src/probe.cpp
    src/guard.cpp
)

target_compile_options(zyrexdump PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti
    -O2
)

# -fno-exceptions/-fno-rtti keep the library small, but std::string and
# std::vector still need the static libc++ runtime linked in.
target_link_options(zyrexdump PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)

target_link_libraries(zyrexdump log)
