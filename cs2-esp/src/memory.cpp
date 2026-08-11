#include "memory.h"

#include <tlhelp32.h>

#include <cwchar>
#include <vector>

Memory::~Memory()
{
    Detach();
}

bool Memory::Attach(const wchar_t* processName)
{
    Detach();

    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);

    if (snapshot == INVALID_HANDLE_VALUE)
        return false;

    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);

    DWORD found = 0;

    if (Process32FirstW(snapshot, &entry))
    {
        do
        {
            if (_wcsicmp(entry.szExeFile, processName) == 0)
            {
                found = entry.th32ProcessID;
                break;
            }
        }
        while (Process32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);

    if (found == 0)
        return false;

    m_handle = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, found);

    if (m_handle == nullptr)
        return false;

    m_pid = found;
    return true;
}

void Memory::Detach()
{
    if (m_handle != nullptr)
    {
        CloseHandle(m_handle);
        m_handle = nullptr;
    }

    m_pid = 0;
}

bool Memory::IsAlive() const
{
    if (m_handle == nullptr)
        return false;

    DWORD code = 0;

    if (!GetExitCodeProcess(m_handle, &code))
        return false;

    return code == STILL_ACTIVE;
}

uintptr_t Memory::GetModuleBase(const wchar_t* moduleName) const
{
    if (m_pid == 0)
        return 0;

    HANDLE snapshot = INVALID_HANDLE_VALUE;

    // Toolhelp fails with ERROR_BAD_LENGTH while the target is still mapping
    // modules in; a short retry loop rides that out.
    for (int attempt = 0; attempt < 16; ++attempt)
    {
        snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, m_pid);

        if (snapshot != INVALID_HANDLE_VALUE)
            break;

        if (GetLastError() != ERROR_BAD_LENGTH)
            return 0;

        Sleep(50);
    }

    if (snapshot == INVALID_HANDLE_VALUE)
        return 0;

    MODULEENTRY32W entry{};
    entry.dwSize = sizeof(entry);

    uintptr_t base = 0;

    if (Module32FirstW(snapshot, &entry))
    {
        do
        {
            if (_wcsicmp(entry.szModule, moduleName) == 0)
            {
                base = reinterpret_cast<uintptr_t>(entry.modBaseAddr);
                break;
            }
        }
        while (Module32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);
    return base;
}

bool Memory::ReadRaw(uintptr_t address, void* buffer, size_t size) const
{
    if (m_handle == nullptr || address < 0x10000)
        return false;

    SIZE_T read = 0;

    if (!ReadProcessMemory(m_handle, reinterpret_cast<LPCVOID>(address), buffer, size, &read))
        return false;

    return read == size;
}

std::string Memory::ReadString(uintptr_t address, size_t maxLength) const
{
    if (maxLength == 0)
        return {};

    std::vector<char> buffer(maxLength + 1, '\0');

    if (!ReadRaw(address, buffer.data(), maxLength))
        return {};

    buffer[maxLength] = '\0';
    return std::string(buffer.data());
}
