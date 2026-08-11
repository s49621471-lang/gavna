#pragma once

struct Config
{
    bool  enabled        = true;
    bool  drawBox        = true;
    bool  drawHealthBar  = true;
    bool  drawName       = true;
    bool  drawSkeleton   = true;
    bool  drawSnaplines  = false;
    bool  drawDistance   = true;
    bool  drawWeapon     = false;
    bool  teamCheck      = true;   // skip teammates
    bool  showFps        = true;

    float maxDistance    = 4000.f; // units; 0 disables the cull
    float boxThickness   = 1.6f;
    float skeletonWidth  = 1.4f;
    float fontSize       = 13.f;

    int   updateRateHz   = 240;    // memory poll rate
};

extern Config g_config;
