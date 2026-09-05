# Test fixtures

`core/common/src/test/resources/fixtures/` holds artifacts produced by real Android
tooling, not hand-assembled bytes. Regenerate with `tools/gen-fixtures.sh`.

| File | Produced by | Purpose |
|---|---|---|
| `sample-base.apk` | `aapt2 link` (build-tools 36.0.0) | Base manifest: components, filters, deep links, meta-data |
| `sample-split-abi.apk` | `aapt2 link` | `config.arm64_v8a` configuration split |
| `sample-split-feature.apk` | `aapt2 link` | `isFeatureSplit` dynamic feature |
| `sample-labelled.apk` | `aapt2 compile` + `link` | The only fixture with a **resource table**: `android:label="@string/app_name"`, which is how real apps name themselves and the shape that made every imported app show as `@7f010000` |
| `libprobe16k.so` | NDK r27 clang, `-z max-page-size=16384` | 16 KB-aligned arm64 library (Android 15/16) |
| `libprobe4k.so` | NDK r27 clang, `-z max-page-size=4096` | 4 KB-aligned arm64 library that will not load on a 16 KB device |
