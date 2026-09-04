# Google compatibility — the run that would settle it

Nothing in the Google Compatibility Layer is claimed to work. This document says exactly
what is implemented, what has been checked, why it cannot be checked here, and what a
person with the right device would have to do to turn any of it into a fact.

## Why not here

The verification environment is an `aosp_atd` Android 14 emulator. It has no Google stack
at all — no `com.google.android.gms`, no `com.android.vending`, no `com.google.android.gsf`
— so every Google claim made against it would be speculation dressed as a result.

A Google APIs or Google Play system image would fix that, and the SDK offers both
(`system-images;android-34;google_apis;x86_64` and `...;google_apis_playstore;x86_64`).
They were **not** installed here, and the reason is worth writing down rather than
implying it was an oversight: the container has ~4 GB of writable disk left and the
existing `aosp_atd` image alone occupies 8.2 GB. A partially-extracted second image would
leave neither emulator usable, and losing the environment that produces the twenty-eight
device-proven results would be a bad trade for a partial Google answer.

So: the parts that can be checked without a Google stack are checked, and the rest is
written down here as a procedure rather than asserted.

## What is implemented, and what that means

| Piece | State | What it does |
|---|---|---|
| `GoogleCompatRouter` | Implemented, unit-tested | Chooses a mode per flow from the app's own manifest plus what the device can do. No package-name special cases; policy overrides come from the compatibility database (§13) |
| `GoogleEnvironment` | Implemented, **device-checked on a device with no GMS** | Reads the host: is GMS present, enabled, and new enough to be usable; is there a Custom Tabs browser. "Present but disabled" and "present but a stub" are distinguished, because both report as installed and answer nothing |
| Guest's view of the Google stack | Device-proven (`t21`) | A virtualized app is told the truth about this device — including the *absence* of Play services. An app told GMS is present when it is not fails later, somewhere much less obvious |
| `GoogleAuthBridge` and the other four bridges | **Interfaces only** | Every method returns `GoogleResult.Unsupported` with the flow named. Nothing pretends |

The router's decision for a given app is recorded on the `GOOGLE` diagnostics channel as
`GOOGLE_ROUTE flow=… mode=… why=…`, so what UNIQUE *intended* is always visible even when
nothing was served.

## What you need to change any of this

- A physical ARM64 phone with Google Play services, signed in to a Google account you are
  willing to use for testing. **Not a personal account** — the flows below hand tokens to
  a virtualized app, and although UNIQUE stores nothing (see *Data handling* below), the
  right posture for an auth experiment is a throwaway account.
- Alternatively an emulator started from `system-images;android-34;google_apis;x86_64`
  (Play services, no Play Store — enough for everything except Play Billing and Play
  Integrity) or `google_apis_playstore` (adds the Store, but the image is locked, so no
  `adb root`).
- A sample app for each flow. UNIQUE ships none: a Google Sign-In sample needs an OAuth
  client id tied to a package name and a signing certificate, and those are yours, not
  something that can live in this repository.

## The order to try them in, and what each answers

Each step is independent, and each has a different chance of working. Do them in this
order because a failure in an early one explains the later ones.

### 1. Does a virtualized app see Play services at all?

Import any app and open **App Details → Google**. The panel is rendered from
`GoogleEnvironment` and the router, so it says what UNIQUE believes about the device.
Then launch the probe with the identity check and read `probe-identity.properties`:

```
present.com.google.android.gms=true
present.com.android.vending=true
present.com.google.android.gsf=true
```

**This is the only step that already has a device-proven counterpart.** On the emulator
all three read `false`, which is correct there, and `t21` asserts the guest's answer
equals the host's.

If a guest reads `false` on a device where the host reads `true`, stop: nothing further
can work, and the fault is in the virtual `PackageManager`, not in the Google layer.

### 2. Can the guest bind to a GMS service?

Sign-in, Credential Manager and Firebase Auth all end in a bind to a service exported by
`com.google.android.gms`. The bind carries UNIQUE's uid and UNIQUE's package name, because
that is what the platform sees. Expect one of:

- **It binds.** GMS does not check the caller for the availability APIs, and the layer's
  central hypothesis survives its first test.
- **`SecurityException` or a silent failure.** GMS checked the caller's identity, and Mode
  B needs the caller to *be* the app — which it cannot be. Record the exact exception:
  which check failed decides whether Mode A (an in-space GMS) is the only route.

### 3. Google Sign-In (the legacy `GoogleSignInClient`)

Expected to fail, and expected to fail *specifically*: the OAuth client id is bound to
`(package name, signing certificate SHA-1)`, and GMS computes both from the calling uid.
A virtualized app calls as UNIQUE. So the token, if one is issued at all, is issued to
UNIQUE — the wrong audience.

`GoogleResult.WrongAudience` exists for exactly this and carries the expected and actual
audience, because a token that fails server-side with no client-side error is the worst
possible outcome and the one this would otherwise produce.

Record: whether a token came back, what audience it names, and whether the app's own
backend accepted it.

### 4. Credential Manager (`androidx.credentials`)

**The layer's central hypothesis, and the reason to do this run at all.** Credential
Manager routes through the platform rather than through a GMS binding whose caller is
checked the same way, and its Google id-token provider takes the server client id from
the *request* rather than from the calling package. If that holds, sign-in works for a
virtualized app in a way `GoogleSignInClient` structurally cannot.

It is a hypothesis. It has never been tested. Test it before anything else in this list is
worth building on.

### 5. Firebase Auth

Firebase Auth with a Google credential is step 3 or 4 plus a Firebase project. If 4 works,
this should follow; if only 3 works, this inherits the wrong-audience problem.

### 6. OAuth in a browser (Mode C)

The fallback that depends on no Google component at all: open the provider's authorization
URL in a Custom Tab, come back through a deep link. What it needs from UNIQUE is that a
deep link *reaches the guest*, which is the implicit-intent path — currently `NOT_TESTED`
(see `docs/COMPATIBILITY.md`). Test that first, with any `https` deep link, before
blaming OAuth.

### 7. FCM

Needs a registration bound to a package name and a certificate, so it inherits step 3's
problem, plus a Firebase project and a network. Left last on purpose.

## Data handling during these tests

The rules the rest of UNIQUE follows apply here with no exceptions, and they are the
reason this document is a procedure rather than a feature:

- **No token, credential, cookie or account is written to world-readable storage.**
  Anything an instance stores lives under UNIQUE's app-private directory, in that
  instance's own tree.
- **The diagnostics export contains no secrets.** It carries UNIQUE's structured logs and
  device facts and nothing from inside an instance's data directory; every line goes
  through the redactor first. Attach one to whatever you report — see
  `docs/PHYSICAL_DEVICE_TEST.md`.
- **Do not paste a token into an issue.** Record the *audience* and the *error*, which is
  what anyone reading needs.

## What to write down

For each step: the device, the Play services version code, what happened, and the exact
exception or token audience. Then update the Google rows in `docs/COMPATIBILITY.md` — and
only those rows, and only with what was observed. A flow moves from `NOT_TESTED` to
`SUPPORTED`, `PARTIAL` or `BROKEN`; it never moves because it seems like it should work.
