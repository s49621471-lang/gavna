cmake_minimum_required(VERSION 3.20)

project(cs2-esp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(cs2-esp
    src/main.cpp
    src/memory.cpp
    src/offsets.cpp
    src/overlay.cpp
    src/renderer.cpp
    src/sdk.cpp
)

target_include_directories(cs2-esp PRIVATE src)

target_compile_definitions(cs2-esp PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    _CRT_SECURE_NO_WARNINGS
)

# Windows SDK only: D3D11 for the device, DXGI for the composition swapchain,
# DirectComposition for per-pixel alpha, D2D/DWrite for the actual drawing.
target_link_libraries(cs2-esp PRIVATE
    d3d11
    dxgi
    d2d1
    dwrite
    dcomp
    user32
    gdi32
)

if (MSVC)
    target_compile_options(cs2-esp PRIVATE /utf-8 /W4 /permissive-)
    set_property(TARGET cs2-esp PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

# Keep the offset table next to the binary so a game patch is an ini edit.
add_custom_command(TARGET cs2-esp POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory $<TARGET_FILE_DIR:cs2-esp>/config
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${CMAKE_CURRENT_SOURCE_DIR}/config/offsets.ini
            $<TARGET_FILE_DIR:cs2-esp>/config/offsets.ini
)
