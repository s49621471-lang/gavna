#include "overlay.h"

using Microsoft::WRL::ComPtr;

namespace
{
    constexpr wchar_t kClassName[] = L"GavnaOverlayClass";
}

LRESULT CALLBACK Overlay::WndProc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam)
{
    if (msg == WM_NCCREATE)
    {
        auto* create = reinterpret_cast<CREATESTRUCTW*>(lparam);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
    }

    auto* self = reinterpret_cast<Overlay*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));

    switch (msg)
    {
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;

    // Outside menu mode the overlay refuses hit-testing entirely, so clicks
    // reach the game even if the extended style is ever cleared. With the menu
    // up we want those clicks.
    case WM_NCHITTEST:
        if (self == nullptr || !self->m_interactive)
            return HTTRANSPARENT;
        break;

    default:
        break;
    }

    return DefWindowProcW(hwnd, msg, wparam, lparam);
}

bool Overlay::Create(const wchar_t* title)
{
    m_module = GetModuleHandleW(nullptr);

    WNDCLASSEXW wc{};
    wc.cbSize        = sizeof(wc);
    wc.style         = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc   = &Overlay::WndProc;
    wc.hInstance     = m_module;
    wc.hCursor       = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = kClassName;

    if (RegisterClassExW(&wc) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        return false;

    m_hwnd = CreateWindowExW(
        WS_EX_NOREDIRECTIONBITMAP | WS_EX_TOPMOST | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        kClassName,
        title,
        WS_POPUP,
        0, 0, 100, 100,
        nullptr, nullptr, m_module, this);

    if (m_hwnd == nullptr)
        return false;

    if (!CreateDevices())
    {
        Destroy();
        return false;
    }

    ShowWindow(m_hwnd, SW_SHOWNOACTIVATE);
    UpdateWindow(m_hwnd);

    return true;
}

bool Overlay::CreateDevices()
{
    const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0,
    };

    HRESULT hr = D3D11CreateDevice(
        nullptr,
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        levels,
        ARRAYSIZE(levels),
        D3D11_SDK_VERSION,
        &m_device,
        nullptr,
        &m_deviceContext);

    if (FAILED(hr))
        return false;

    ComPtr<IDXGIDevice> dxgiDevice;

    if (FAILED(m_device.As(&dxgiDevice)))
        return false;

    if (FAILED(DCompositionCreateDevice(dxgiDevice.Get(), IID_PPV_ARGS(&m_compDevice))))
        return false;

    if (FAILED(m_compDevice->CreateTargetForHwnd(m_hwnd, TRUE, &m_compTarget)))
        return false;

    if (FAILED(m_compDevice->CreateVisual(&m_compVisual)))
        return false;

    D2D1_FACTORY_OPTIONS options{};
    options.debugLevel = D2D1_DEBUG_LEVEL_NONE;

    if (FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, options, m_d2dFactory.GetAddressOf())))
        return false;

    if (FAILED(m_d2dFactory->CreateDevice(dxgiDevice.Get(), &m_d2dDevice)))
        return false;

    if (FAILED(m_d2dDevice->CreateDeviceContext(D2D1_DEVICE_CONTEXT_OPTIONS_NONE, &m_d2dContext)))
        return false;

    // Work in raw pixels; the projection maths already lives in pixel space.
    m_d2dContext->SetUnitMode(D2D1_UNIT_MODE_PIXELS);
    m_d2dContext->SetDpi(96.f, 96.f);
    m_d2dContext->SetAntialiasMode(D2D1_ANTIALIAS_MODE_PER_PRIMITIVE);
    m_d2dContext->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE);

    if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory),
                                   reinterpret_cast<IUnknown**>(m_dwrite.GetAddressOf()))))
        return false;

    return true;
}

bool Overlay::Resize(int width, int height)
{
    if (width <= 0 || height <= 0)
        return false;

    m_d2dContext->SetTarget(nullptr);
    m_target.Reset();

    if (m_swapChain == nullptr)
    {
        ComPtr<IDXGIDevice>  dxgiDevice;
        ComPtr<IDXGIAdapter> adapter;
        ComPtr<IDXGIFactory2> factory;

        if (FAILED(m_device.As(&dxgiDevice)))
            return false;

        if (FAILED(dxgiDevice->GetAdapter(&adapter)))
            return false;

        if (FAILED(adapter->GetParent(IID_PPV_ARGS(&factory))))
            return false;

        DXGI_SWAP_CHAIN_DESC1 desc{};
        desc.Width            = static_cast<UINT>(width);
        desc.Height           = static_cast<UINT>(height);
        desc.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
        desc.Stereo           = FALSE;
        desc.SampleDesc.Count = 1;
        desc.BufferUsage      = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount      = 2;
        desc.Scaling          = DXGI_SCALING_STRETCH;
        desc.SwapEffect       = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
        desc.AlphaMode        = DXGI_ALPHA_MODE_PREMULTIPLIED;

        if (FAILED(factory->CreateSwapChainForComposition(m_device.Get(), &desc, nullptr, &m_swapChain)))
            return false;

        if (FAILED(m_compVisual->SetContent(m_swapChain.Get())))
            return false;

        if (FAILED(m_compTarget->SetRoot(m_compVisual.Get())))
            return false;

        if (FAILED(m_compDevice->Commit()))
            return false;
    }
    else
    {
        if (FAILED(m_swapChain->ResizeBuffers(0, static_cast<UINT>(width), static_cast<UINT>(height),
                                              DXGI_FORMAT_UNKNOWN, 0)))
            return false;
    }

    m_width  = width;
    m_height = height;

    return true;
}

bool Overlay::TrackTarget(HWND target)
{
    if (target == nullptr || !IsWindow(target))
        return false;

    RECT client{};

    if (!GetClientRect(target, &client))
        return false;

    const int width  = client.right - client.left;
    const int height = client.bottom - client.top;

    if (width <= 0 || height <= 0)   // minimised
        return false;

    POINT topLeft{ client.left, client.top };

    if (!ClientToScreen(target, &topLeft))
        return false;

    const bool sizeChanged = (width != m_width || height != m_height);
    const bool moved       = (topLeft.x != m_posX || topLeft.y != m_posY);

    if (sizeChanged || moved)
    {
        SetWindowPos(m_hwnd, HWND_TOPMOST, topLeft.x, topLeft.y, width, height,
                     SWP_NOACTIVATE | SWP_SHOWWINDOW);

        m_posX = topLeft.x;
        m_posY = topLeft.y;

        if (sizeChanged && !Resize(width, height))
            return false;
    }
    else if (GetForegroundWindow() == target)
    {
        // The game re-asserts topmost when it takes focus; climb back above it.
        SetWindowPos(m_hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                     SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }

    return true;
}

void Overlay::SetInteractive(bool interactive, HWND restoreFocusTo)
{
    if (m_hwnd == nullptr || interactive == m_interactive)
        return;

    m_interactive = interactive;

    LONG_PTR style = GetWindowLongPtrW(m_hwnd, GWL_EXSTYLE);

    if (interactive)
    {
        // Dropping NOACTIVATE lets the overlay take foreground, which makes the
        // game release its cursor clip and stop swallowing the pointer.
        style &= ~(WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
        SetWindowLongPtrW(m_hwnd, GWL_EXSTYLE, style);

        ClipCursor(nullptr);
        SetForegroundWindow(m_hwnd);
        SetActiveWindow(m_hwnd);
    }
    else
    {
        style |= WS_EX_TRANSPARENT | WS_EX_NOACTIVATE;
        SetWindowLongPtrW(m_hwnd, GWL_EXSTYLE, style);

        if (restoreFocusTo != nullptr && IsWindow(restoreFocusTo))
            SetForegroundWindow(restoreFocusTo);
    }

    // Extended-style changes only take effect on the next frame-change pass.
    SetWindowPos(m_hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOACTIVATE);
}

Vec2 Overlay::CursorPosition() const
{
    POINT point{};

    if (m_hwnd == nullptr || !GetCursorPos(&point) || !ScreenToClient(m_hwnd, &point))
        return { -1.f, -1.f };

    return { static_cast<float>(point.x), static_cast<float>(point.y) };
}

bool Overlay::PumpMessages()
{
    MSG msg{};

    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE))
    {
        if (msg.message == WM_QUIT)
            return false;

        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    return true;
}

bool Overlay::BeginFrame()
{
    if (m_swapChain == nullptr || m_d2dContext == nullptr)
        return false;

    // Flip-model rotates buffers, so the D2D target is rebuilt from the current
    // back buffer every frame.
    ComPtr<IDXGISurface> surface;

    if (FAILED(m_swapChain->GetBuffer(0, IID_PPV_ARGS(&surface))))
        return false;

    const D2D1_BITMAP_PROPERTIES1 props = D2D1::BitmapProperties1(
        D2D1_BITMAP_OPTIONS_TARGET | D2D1_BITMAP_OPTIONS_CANNOT_DRAW,
        D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED),
        96.f, 96.f);

    if (FAILED(m_d2dContext->CreateBitmapFromDxgiSurface(surface.Get(), &props, &m_target)))
        return false;

    m_d2dContext->SetTarget(m_target.Get());
    m_d2dContext->BeginDraw();
    m_d2dContext->Clear(D2D1::ColorF(0.f, 0.f, 0.f, 0.f));

    m_drawing = true;
    return true;
}

void Overlay::EndFrame()
{
    if (!m_drawing)
        return;

    m_d2dContext->EndDraw();
    m_d2dContext->SetTarget(nullptr);
    m_target.Reset();

    m_swapChain->Present(1, 0);
    m_compDevice->Commit();

    m_drawing = false;
}

void Overlay::Destroy()
{
    if (m_d2dContext != nullptr)
        m_d2dContext->SetTarget(nullptr);

    m_target.Reset();
    m_dwrite.Reset();
    m_d2dContext.Reset();
    m_d2dDevice.Reset();
    m_d2dFactory.Reset();
    m_compVisual.Reset();
    m_compTarget.Reset();
    m_compDevice.Reset();
    m_swapChain.Reset();
    m_deviceContext.Reset();
    m_device.Reset();

    if (m_hwnd != nullptr)
    {
        DestroyWindow(m_hwnd);
        m_hwnd = nullptr;
    }

    UnregisterClassW(kClassName, m_module);
}
