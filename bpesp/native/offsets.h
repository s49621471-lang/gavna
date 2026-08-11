// BLOCKPOST 1.00f3 (com.skullcapstudios.bps) — player entity layout, arm64.
// Derived from a runtime field dump; obfuscated IL2CPP names in comments.
#pragma once

#define OFF_SLOT         0x10   // KPCNEILKJMI  int    entity index 0..N
#define OFF_NAME         0x18   // MLJCHMCBBJF  string
#define OFF_DISPLAY_NAME 0x20   // BJMPENGFNPN  string  name + " [MODE]"
#define OFF_NO_TRANSFORM 0x28   // HDKAKDBMGHK  bool    set => position is not replicated
#define OFF_WALKING      0x40   // MEJEPLNLFIB  int
#define OFF_ACCOUNT_ID   0x44   // DIOIFFMPJIG  int
#define OFF_LEVEL        0x48   // DAPCOEFGHIP  int
#define OFF_SKIN         0x4C   // NIOMPJLANIM  int
#define OFF_ACTION       0x50   // DPDDICEGNKC  int
#define OFF_COSMETIC     0x54   // BKEKILKIBMB  int
#define OFF_HEALTH       0x58   // KAEOBIMDFOF  int    0..100
#define OFF_ARMOR        0x5C   // JOICDOIGMEP  int    0..100
#define OFF_KILLS        0x60   // IMHHIKCBKLH  int
#define OFF_SPRINTING    0x64   // EGLJCJKFNCE  int
#define OFF_DEATHS       0x68   // BFEDGEJJPFD  int
#define OFF_MONEY        0x6C   // CNMGDBLDBNP  int
#define OFF_NETPOS_FROM  0x98   // EECHFLLCCBD  Vector3  interpolation source
#define OFF_NETPOS_TO    0xA4   // BCNLIGEEEIL  Vector3  interpolation target
#define OFF_POSITION     0xB0   // HBFFGFGNIPL  Vector3  <- render from this
#define OFF_AIM_AXIS_Y   0xE8   // LIMMHEBPHKK  float
#define OFF_PITCH        0xEC   // BKNGOBMDOPE  float    degrees
#define OFF_LOOK_DIR     0xF0   // GDNOIACGMNP  Vector3  unit
#define OFF_PITCH_PREV   0xFC   // HIPCGEGEMLB  float
#define OFF_DIST_LIFE    0x110  // NDDBDABLHBM  float    metres this life
#define OFF_MATERIALS    0x120  // EIOEIICLFGK  Material[]  (local only)
#define OFF_MESH         0x130  // GKNBJMGAKEJ  SkinnedMeshRenderer (local only)
#define OFF_WEAPON       0x138  // EKONIDLOCCD  weapon instance
#define OFF_WEAPON_ARRAY 0x140  // HDGDHMCBNDO  weapon[]
#define OFF_GAMEOBJECT   0x150  // GPJPDLEIHDJ  GameObject (local only)
#define OFF_MOVE         0x158  // JOLHFJIHBCN  NMAMove    (local only) -> local player test
#define OFF_SCORE        0x160  // IKOMNHGMKNL  int

#define PLAYER_MIN_SIZE  0x168

// Obfuscated field names as dumped. Used to re-resolve offsets at runtime so a
// patch that only shifts the layout still works without a rebuild.
#define NAME_SLOT        "KPCNEILKJMI"
#define NAME_NAME        "MLJCHMCBBJF"
#define NAME_DISPLAY     "BJMPENGFNPN"
#define NAME_HEALTH      "KAEOBIMDFOF"
#define NAME_ARMOR       "JOICDOIGMEP"
#define NAME_KILLS       "IMHHIKCBKLH"
#define NAME_DEATHS      "BFEDGEJJPFD"
#define NAME_MONEY       "CNMGDBLDBNP"
#define NAME_POSITION    "HBFFGFGNIPL"
#define NAME_LOOK_DIR    "GDNOIACGMNP"
#define NAME_MOVE        "JOLHFJIHBCN"
#define NAME_SCORE       "IKOMNHGMKNL"
#define NAME_PITCH       "BKNGOBMDOPE"
#define NAME_DIST_LIFE   "NDDBDABLHBM"
#define NAME_ACCOUNT_ID  "DIOIFFMPJIG"
#define NAME_LEVEL       "DAPCOEFGHIP"
#define NAME_WEAPON      "EKONIDLOCCD"
#define NAME_NO_TRANSFORM "HDKAKDBMGHK"

// Model metrics (Blockpost characters are ~1.8 units tall, origin at the feet).
#define PLAYER_HEIGHT    1.80f
#define PLAYER_WIDTH_RATIO 0.48f
