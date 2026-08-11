#pragma once

#include <Windows.h>

#include <cstdint>
#include <string>
#include <type_traits>

class Memory
{
public:
    ~Memory();

    // Finds the process by image name and opens a read handle.
    bool Attach(const wchar_t* processName);
    void Detach();

    bool IsAttached() const { return m_handle != nullptr; }

    // Returns false once the target has exited.
    bool IsAlive() const;

    DWORD  Pid()    const { return m_pid; }
    HANDLE Handle() const { return m_handle; }

    // Base address of a loaded module inside the target, 0 when not found.
    uintptr_t GetModuleBase(const wchar_t* moduleName) const;

    bool ReadRaw(uintptr_t address, void* buffer, size_t size) const;

    template <typename T>
    T Read(uintptr_t address) const
    {
        static_assert(std::is_trivially_copyable_v<T>, "Read<T> requires a trivially copyable type");

        T value{};

        if (!ReadRaw(address, &value, sizeof(T)))
            return T{};

        return value;
    }

    // Reads a fixed-size inline char buffer and guarantees termination.
    std::string ReadString(uintptr_t address, size_t maxLength) const;

private:
    DWORD  m_pid    = 0;
    HANDLE m_handle = nullptr;
};
