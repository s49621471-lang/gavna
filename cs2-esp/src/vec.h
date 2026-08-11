#pragma once

#include <cmath>

struct Vec2
{
    float x = 0.f;
    float y = 0.f;
};

struct Vec3
{
    float x = 0.f;
    float y = 0.f;
    float z = 0.f;

    Vec3 operator+(const Vec3& o) const { return { x + o.x, y + o.y, z + o.z }; }
    Vec3 operator-(const Vec3& o) const { return { x - o.x, y - o.y, z - o.z }; }
    Vec3 operator*(float s)      const { return { x * s, y * s, z * s }; }

    float Length() const { return std::sqrt(x * x + y * y + z * z); }

    float DistanceTo(const Vec3& o) const { return (*this - o).Length(); }
};

// Source 2 hands the view matrix over row-major: m[row][col].
struct ViewMatrix
{
    float m[4][4] = {};
};

// Projects a world point into screen space. Returns false when the point sits
// behind the camera plane, in which case out is untouched.
inline bool WorldToScreen(const ViewMatrix& vm, const Vec3& world, int screenW, int screenH, Vec2& out)
{
    const float w = vm.m[3][0] * world.x + vm.m[3][1] * world.y + vm.m[3][2] * world.z + vm.m[3][3];

    if (w < 0.01f)
        return false;

    const float x = vm.m[0][0] * world.x + vm.m[0][1] * world.y + vm.m[0][2] * world.z + vm.m[0][3];
    const float y = vm.m[1][0] * world.x + vm.m[1][1] * world.y + vm.m[1][2] * world.z + vm.m[1][3];

    const float invW = 1.f / w;

    out.x = (static_cast<float>(screenW) * 0.5f) * (1.f + x * invW);
    out.y = (static_cast<float>(screenH) * 0.5f) * (1.f - y * invW);

    return true;
}
