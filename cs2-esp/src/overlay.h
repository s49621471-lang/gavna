#pragma once

#include "vec.h"

#include <windows.h>

#include <d2d1_1.h>
#include <d3d11.h>
#include <dcomp.h>
#include <dwrite.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

// Click-through, per-pixel-alpha overlay built on DirectComposition. No layered
// window colour-key hacks: the swapchain composites straight onto the desktop.
class Overlay
{
public:
    bool Create(const wchar_t* title);
    void Destroy();

    // Moves and resizes the overlay onto the target window's client area.
    // Returns false when the target is gone or minimised.
    bool TrackTarget(HWND target);

    // Interactive mode drops click-through and takes focus so the menu can be
    // used; the game releases the cursor as soon as it loses foreground.
    // Passing false hands focus straight back to restoreFocusTo.
    void SetInteractive(bool interactive, HWND restoreFocusTo);

    bool IsInteractive() const { return m_interactive; }

    // Cursor position in overlay client pixels.
    Vec2 CursorPosition() const;

    // Drains the message queue. Returns false once a quit was posted.
    bool PumpMessages();

    bool BeginFrame();
    void EndFrame();

    ID2D1DeviceContext* Context() const { return m_d2dContext.Get(); }
    IDWriteFactory*     DWrite()  const { return m_dwrite.Get(); }
    ID2D1Factory1*      Factory() const { return m_d2dFactory.Get(); }

    int  Width()  const { return m_width; }
    int  Height() const { return m_height; }
    HWND Hwnd()   const { return m_hwnd; }

private:
    bool CreateDevices();
    bool Resize(int width, int height);

    static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam);

    HWND      m_hwnd   = nullptr;
    HINSTANCE m_module = nullptr;

    int  m_width       = 0;
    int  m_height      = 0;
    int  m_posX        = 0;
    int  m_posY        = 0;
    bool m_interactive = false;

    Microsoft::WRL::ComPtr<ID3D11Device>          m_device;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext>   m_deviceContext;
    Microsoft::WRL::ComPtr<IDXGISwapChain1>       m_swapChain;
    Microsoft::WRL::ComPtr<IDCompositionDevice>   m_compDevice;
    Microsoft::WRL::ComPtr<IDCompositionTarget>   m_compTarget;
    Microsoft::WRL::ComPtr<IDCompositionVisual>   m_compVisual;
    Microsoft::WRL::ComPtr<ID2D1Factory1>         m_d2dFactory;
    Microsoft::WRL::ComPtr<ID2D1Device>           m_d2dDevice;
    Microsoft::WRL::ComPtr<ID2D1DeviceContext>    m_d2dContext;
    Microsoft::WRL::ComPtr<ID2D1Bitmap1>          m_target;
    Microsoft::WRL::ComPtr<IDWriteFactory>        m_dwrite;

    bool m_drawing = false;
};
