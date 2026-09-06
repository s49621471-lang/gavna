# Standoff 2, and how it knows

What this game checks, read out of the game itself, because two passes had been spent
guessing at it. `com.axlebolt.standoff2` 0.39.3, versionCode 203908, ARM64.

Everything below comes from the shipping build: the 105 MB APK and the 1.7 GB expansion
file. Nothing here is inferred from behaviour or from what similar games do, and where a
conclusion is a reading rather than a quotation it says so.

---

## Where the evidence is, and where it is not

The obvious place to look is not the right one. The game is Unity 6 on IL2CPP, and the
build reports its engine as `6000.3.13f1_patched` — a Unity fork of Axlebolt's own — so
the usual layout does not apply:

| Looked for | Found |
|---|---|
| `assets/bin/Data/Managed/Metadata/global-metadata.dat` in the APK | not there |
| the same, in the expansion file | not there — the OBB holds `data.unity3d`, DLC bundles and FMOD banks, and nothing else |
| `libil2cpp.so` | not there |

The metadata is **linked into `libunity.so`**, which is 236 MB in this build against the
20–40 MB an unmodified engine produces. That is also where it is *readable*: the IL2CPP
string, type, method and field name tables are in the binary in the clear. `strings` on it
yields 378,000 runs, and the game's own C# identifiers are among them.

The expansion file therefore answers nothing about detection and did not need to be
downloaded at all. Its index was read over HTTP range requests — 1,384 entries, none of
them metadata — which is recorded here because the next person to ask this question should
not spend 1.7 GB finding out.

---

## The two things that say "virtual space", and they are not the same thing

There are **two** separate mechanisms, with two separate messages, and conflating them
sends any fix to the wrong layer.

### 1. The in-game warning: `Anticheat/VirtualSpaceWarning`

A localization key, in the `Anticheat` section, beside the game's own alert strings:

```
Anticheat \0 get_VirtualSpaceWarning \0 VirtualSpaceWarning \0 ArmsRace \0 …
```

It is set from `Axlebolt.Standoff.Anitcheat.AntiCheatManager` — the namespace is spelled
that way in the binary. The class's names are obfuscated to fifteen-character garbage
(`FBGEDCEFHEBEGGF`), with four survivors that were not renamed because they are used
reflectively or as constants:

```
VirtualSpaceDetected      PackageName
<ScanDirectoryRecursive>b__25_0        <GetUnityPlayer>b__30_0
<UpdateCoroutine>d__4
```

`VirtualSpaceDetected` is the flag. `UpdateCoroutine` means it is re-evaluated on a timer
rather than once at startup. `GetUnityPlayer` means it reaches the Android side through
`com.unity3d.player.UnityPlayer`, and `ScanDirectoryRecursive` means it walks directories.

### 2. The login refusal: `AuthRestrictions/VirtualSpaceMessage`

A different key, in a family that is all about being refused a login:

```
AuthRestrictions
    get_FilesCheckFailedMessage      FilesCheckFailedMessage
    get_FilesNotAuthenticMessage     FilesNotAuthenticMessage
    get_HackingSoftFoundMessage      HackingSoftFoundMessage
    get_RootFoundMessage             RootFoundMessage
    get_UnofficialVersionMessage     UnofficialVersionMessage
    get_VirtualSpaceMessage          VirtualSpaceMessage
```

beside `YouAccountIsBanned`, `YouDeviceIsBanned`, `YourSessionIsKicked` and the
`GooglePlay*` sign-in error codes. These are **server verdicts**: the client shows the
message the server names.

---

## What the client sends, and to which request it attaches it

The report is a protobuf message, and its shape is in the metadata field-name table:

```protobuf
message AppVerification {
  bool            IsRooted;
  string          ApkHash;
  repeated string JsonForbiddenApps;
  string          Path;
  string          ContentHash;
  map<string, …>  AppSnapshot;
  …               N;              // RSA modulus
  …               E;              // RSA exponent
}
```

`N` and `E` are an RSA public key travelling with the report, so the payload is signed or
sealed client-side: it is not a field an intermediary edits.

And this is the part that matters most:

```
GoogleAuthRequest
    get_AuthGoogle  set_AuthGoogle
    get_AppVerification  set_AppVerification
    AuthGoogleFieldNumber  authGoogle_
    AppVerificationFieldNumber  appVerification_
```

**`AppVerification` is a field of `GoogleAuthRequest`.** The environment report is not a
background telemetry ping; it is *part of the sign-in call*. Every auth request shape in
the file — `TestAuthRequest`, `VkAuthRequest`, `FacebookAuthRequest`, `GameCenterAuthRequest`
— carries a `Verification` alongside the credential.

Which means: signing in and being told the game is running in a virtual space are the same
event, seen from two sides. An engine that fixes the crash on the way to the Google account
picker has not touched this at all.

The neighbouring names in the string table name the rest of what is collected:

```
ApkAndObb  ApkCertCompLen  ApkCertLen  ApkFiles  ApkHash  ApkLibs  ApkPath
AppEnvironment  AppProcs  AppSnapshot  AppVerification
```

— the APK's path, its hash, its certificate length, its file and library lists, the OBB
alongside it, and a list of processes.

---

## What it reads to decide, and this is the whole answer

The client's Java-side surface is small and completely explicit. Every JNI name the game
references, counted in `libunity.so`:

| Referenced | Times |
|---|---|
| `sourceDir` | 4 |
| `getApplicationInfo` | 3 |
| `getPackageCodePath` | 2 |
| `getPackageManager` | 2 |
| `currentActivity` | 2 |
| `getPackageName` | 2 |
| `nativeLibraryDir` | 1 |
| `getInstalledApplications` | 1 |
| `getFilesDir` | 1 |
| `getExternalFilesDir` | 1 |

That is the identity-path surface and nothing else. There is no `/proc/self/maps` in it, no
`dl_iterate_phdr`, no mount-table walk. `sourceDir` four times and `getPackageCodePath`
twice, from a class holding a flag called `VirtualSpaceDetected`.

Inside UNIQUE, those four values are:

```
sourceDir          /data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/base.apk
publicSourceDir    …the same
nativeLibraryDir   /data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/lib/arm64-v8a
getFilesDir()      /data/user/0/com.unique/files/virtual/users/0/data/com.axlebolt.standoff2/files
```

Every one of them names another package. An installed copy of this game cannot produce any
of them, no comparison is needed to see it, and the game sends the first of them to its own
server as `Path`.

**So this is the detection.** It is not `/proc`, it is not the process list, it is not an
emulator check. It is four getters.

### The hard-coded path list, which is *not* it

There is one literal list of paths in the binary, pipe-separated:

```
data/user/0/com.kittenware.skillz|data/data/user/0/com.kittenware.skillz|
data/user/0/com.skillz.kitso2hider|data/data/user/0/com.skillz.kitso2hider|
data/user/0/io.va.exposed/virtual/data/user/0/com.kittenware.skillz|
data/user/0/io.va.exposed/virtual/data/user/0/com.skillz.kitso2hider|
/data/data/com.topjohnwu.magisk
```

Two cheat packages, each looked for directly **and** inside VirtualXposed's virtual tree
(`io.va.exposed/virtual/…`), plus Magisk. This is the `CheatDirectories` list, and it is a
*cheat* detector that happens to know one virtualization engine's layout — not a
virtualization detector. UNIQUE's tree is not in it, and adding UNIQUE to it would take
someone at Axlebolt writing it down. It is recorded here so that it is not mistaken for the
mechanism above.

### Play Integrity is present, and is a separate ceiling

`Google.Play.Integrity`, `IntegrityManager`, `RequestIntegrityToken`, `environmentIntegrity`
and `GooglePlayIntegrityCheckRpcException` are all in the build. Play Integrity attests the
*calling package and certificate*, which inside UNIQUE is UNIQUE's. Nothing in this engine
changes that, and nothing here should be read as suggesting otherwise — `README.md` says
UNIQUE is not an attestation bypass, and that stands.

---

## What this means for UNIQUE, stated as a decision and not as a plan

The `/proc` view shipped one pass earlier closes a real vector and closes nothing this game
uses. That is worth saying plainly rather than quietly leaving it as an implied win: it was
built from reasoning about what a check *would* read, and the check reads something else.
It stays, because the reasoning was right about the class of app and wrong only about this
one, and because the leak it closes is real.

The change this game needs is the one already recorded as the next step in `STATUS.md`, now
with evidence behind it instead of a guess:

**A guest's Java-visible paths have to be shaped like an installed app's, and those paths
have to resolve.**

The second half is what makes it work rather than a cosmetic change, and it is the hard
half. There is no directory on the device that UNIQUE can create and that does not name
`com.unique`: `/data/app/…` and `/data/user/0/<guest>` both belong to the platform. So the
public path can only be made real by redirection — and the redirection UNIQUE has is a PLT
patch in the *guest's own* libraries, which does not cover the framework's own opens. The
class loader (`DexPathList`), `AssetManager.addAssetPath` and every `java.io.File` in the
guest go through `libjavacore.so` and `libandroidfw.so`, which are the platform's.

So it needs the interception widened to those libraries, with UNIQUE's own file operations
in the same process exempted. That is the VirtualApp architecture and it is a large change
with the largest possible blast radius — every file operation of every guest. It is not
one to make in the same build as three other fixes, because a log that then goes wrong
would not say which change did it.

What it is *not* is speculative any more. The surfaces are the four getters above, the
report field is `Path`, and the request it rides on is `GoogleAuthRequest`.

---

## How to reproduce every claim here

```bash
# 1. The APK. 105 MB.
#    (the OBB is not needed; its index says why — see the table at the top)

# 2. The metadata is in libunity.so, in the clear.
unzip -o app.apk 'lib/arm64-v8a/libunity.so' -d x
strings -n 5 x/lib/arm64-v8a/libunity.so > s.txt
wc -l s.txt                       # ~378,000

# 3. The two messages.
grep -aoE '[A-Z][A-Za-z0-9]+/[A-Z][A-Za-z0-9]+' s.txt | grep -i virtualspace
#   Anticheat/VirtualSpaceWarning
#   AuthRestrictions/VirtualSpaceMessage   (as ...get_VirtualSpaceMessage)

# 4. The detector's surviving member names.
python3 - <<'PY'
import re
d = open('x/lib/arm64-v8a/libunity.so','rb').read()
i = d.find(b'AntiCheatManager')
print(d[i-200:i+400])          # …AntiCheatManager Axlebolt.Standoff.Anitcheat…
j = d.find(b'VirtualSpaceDetected')
print(d[j-80:j+200])           # VirtualSpaceDetected PackageName ScanDirectoryRecursive
PY

# 5. The report, and the request it belongs to.
grep -ao 'AppVerification' s.txt | head
python3 - <<'PY'
import re
d = open('x/lib/arm64-v8a/libunity.so','rb').read()
i = d.find(b'\x00AppVerification\x00')
print(b' '.join(p for p in d[i:i+1200].split(b'\x00') if p).decode('latin-1'))
PY

# 6. The Java surface it reads, and the counts in the table above.
for n in sourceDir getPackageCodePath nativeLibraryDir getApplicationInfo \
         getInstalledApplications getFilesDir currentActivity; do
  printf '%s\t%s\n' "$(grep -ac "$n" s.txt)" "$n"
done

# 7. The hard-coded cheat-path list.
grep -ao 'io.va.exposed[^ ]*' s.txt | head -1
```
