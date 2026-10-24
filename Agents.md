# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## Project overview

小鲸鱼输入法 (Whale IME) is an offline Android Chinese input method built on a precompiled Rime engine, written in Kotlin. The repository is a monorepo: a thin application shell (`app`) plus the full IME implementation as an Android library module (`whalesdk`), merged from a fork of `gurecn/whalesdk` (full history preserved via git subtree).

There are no unit/instrumentation tests (`src/test`, `src/androidTest` do not exist). Verification is done by building and running on a device.

## Build and run

Plain clone — no submodules.

Common commands:

```sh
./gradlew :app:assembleOfflineDebug      # build debug APK
./gradlew :app:assembleOfflineRelease    # build minified/signed release APK
./gradlew :app:installOfflineDebug       # install and launch on device
./gradlew clean
./gradlew :whalesdk:makeaar              # build SDK AAR into whalesdk/build/outputs/aar/
```

Notes:

- Local build/deploy pitfalls (missing keystore, JVM target mismatch, settings white-screen crash loop, emulator's phantom physical keyboard, IME re-binding after force-stop) are documented in [`docs/build-and-run.md`](docs/build-and-run.md). Read it before debugging "it builds but nothing works".
- The only product flavor is `offline`; build types are `debug` and `release`, so task names always include `Offline`.
- `versionCode`/`versionName` are derived from the current date (`yyyyMMddHH`, GMT+8) in `app/build.gradle:101`; they are not manually maintained.
- `applicationId` suffixes are `.offline` (flavor), `.debug`/`.release` (build type), e.g. `com.whale.pinyin.offline.release`.
- Release signing is read from `keystore/keystore.properties` (gitignored) or the env vars `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. `debug` also uses the release signing config, so a debug build without a keystore is unsigned.
- Build config compiles against Java 21 (`sourceCompatibility`/`jvmTarget = 21`); CI uses JDK 21. Use JDK 21 locally.
- `whalesdk` packages native libs from `whalesdk/libs/<abi>` only via `sourceSets.offline.jniLibs.srcDirs`, and builds `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. The app module filters to `arm64-v8a` only. The Rime engine (`libwhaleime.so`) is rebuilt out-of-band via `scripts/build-engine.ps1` (NDK/CMake scripts under `whalesdk/src/main/cpp/scripts/`), never via Gradle `externalNativeBuild`.
- CI (`.github/workflows/build.yml`) runs `./gradlew assembleOffline` on `v*` tags and uploads the APKs.

## Architecture

### Module layout

- `app/` — application shell only: `BaseApplication` (calls `Launcher.instance.initData`), the IME service declaration, and `res/xml/method.xml`. Nearly all code and resources live in the SDK.
- `whalesdk/src/main/java/com/whale/imemodule/` — Android/UI layer: service, keyboard views, settings, preferences, themes, Room database.
- `whalesdk/src/main/java/com/whale/inputmethod/` — engine layer: `RimeEngine`, `HWEngine`, `CustomEngine`, JNI bindings and pinyin-key conversion utilities.
- `whalesdk/src/main/assets/rime` — prebuilt Rime schemas/dictionaries (~112 MB), plus `opencc` and `hw` handwriting data. These are copied to `getExternalFilesDir("rime"/"hw")` on first launch, versioned by `CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM`.

### Startup

`BaseApplication.onCreate` → `Launcher.initData` (`whalesdk/.../application/Launcher.kt:21`):

1. Synchronously initializes `AppPrefs`, `ThemeManager`, opens Room (`DataBaseKT`), and `ClipboardHelper`.
2. On a background thread, copies `assets/rime` and `assets/hw` when the stored dict version is stale, then calls `InputDispatcher.resetIme()` to start the native Rime engine, initializes EmojiCompat, and applies dark-mode settings.

`ImeService` is the `InputMethodService`. It is declared in `app/src/main/AndroidManifest.xml` with `BIND_INPUT_METHOD` and `@xml/method` — the SDK manifest deliberately does not declare it. Any host app must add this service entry and the `res/xml/method.xml` metadata.

### Input data flow

```
Soft key tap (TextKeyboard)
  → InputView.responseKeyEvent / processKeyUp        (keyboard/InputView.kt:311)
  → InputDispatcher                                  (service/InputDispatcher.kt:7)
  → RimeEngine (candidate list, composition, case)   (inputmethod/RimeEngine.kt:19)
  → Rime JNI → libwhaleime.so                        (inputmethod/core/Rime.kt:8)
  → DictDecoder.candidatesLiveData                   (service/DictDecoder.kt:10)
  → InputView.onCandidateChanged → candidate bar / CandidatesContainer
  → ImeService.commitText / setComposingText         (service/ImeService.kt:274)
```

`InputDispatcher` is the single choke point between UI and engine: every key, delete, candidate selection, schema switch and association query goes through it. `DictDecoder` owns the observable candidate state (`LiveData`) and is observed by `ImeService`; `RimeEngine` owns Rime policy (composition string, casing, custom phrase injection, next-page paging). Keep this split when adding input features.

Rime schemas are identified by string IDs in `CustomConstant` (`application/CustomConstant.kt`): `t9_pinyin`, `pinyin`, `english`, `stroke`, `handwriting`, and `double_pinyin_*` variants. The active schema is persisted in `AppPrefs.internal.pinyinModeRime` and applied in `InputModeSwitcher.saveInputMode`.

`HWEngine` drives the separate handwriting native libraries and returns candidates into the same `DictDecoder` pipeline. `CustomEngine` adds non-Rime candidates: user phrases from Room, date/time shortcuts, and expression calculation on the number keyboard.

### Keyboard UI

- `InputView` (`keyboard/InputView.kt:72`) is the root IME view: candidates bar, one-handed/float layout, full-display navigation bar, and key-event handling.
- `KeyboardManager` (`keyboard/KeyboardManager.kt:20`) maps `KeyboardType` (T9, QWERTY, LX17, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT) to lazily created `BaseContainer`s and caches them.
- `KeyboardLoaderUtil` builds `SoftKeyboard`/`SoftKey` objects from `KeyboardData` and caches them by layout mask; layouts are selected by `InputModeSwitcher` bitmasks (`MASK_SKB_LAYOUT_*`).
- `EnvironmentSingleton` computes all pixel dimensions (keyboard height, candidates area, key margins, float/landscape ratios) from preferences and screen size. After changing layout, ratio, one-hand or theme settings, code typically calls `EnvironmentSingleton.initData()` + `KeyboardLoaderUtil.clearKeyboardMap()` + `KeyboardManager.clearKeyboard()`.
- `ThemeManager` holds built-in/custom themes and notifies listeners; `InputView.updateTheme` applies colors and backgrounds.

### Preferences and settings UI

Preferences use a declarative wrapper instead of raw `SharedPreferences`:

- `AppPrefs` (`prefs/AppPrefs.kt`) groups settings into `Internal`, `Input`, `KeyboardSetting`, `Clipboard`, `Handwriting`, `Voice`, `Other`; each field is a `ManagedPreference` (`PBool`, `PInt`, `PString`, `PStringLike`) registered with UI metadata at the same time.
- `ManagedPreferenceFragment` renders the UI automatically from the registered providers, and `ManagedPreferenceVisibilityEvaluator` enables/disables entries via lambdas such as `{ clipboardListening.getValue() }`.
- Settings screens are Fragments wired in `res/navigation/settings_nav.xml`; the root is `ImeSettingsFragment`. Add a new setting by declaring it in the relevant `AppPrefs` category and adding a destination/preference entry only if it needs a new screen.

`AppPrefs.getInstance()` requires `AppPrefs.init` to have run (done by `Launcher`); do not use it before startup.

### Persistence

- Room database `ime_db`, version 5, in `database/DataBaseKT.kt`. Entities: `SideSymbol`, `Clipboard`, `UsedSymbol`, `Phrase`, `SkbFun`, `UserWord`. Schema changes require a new `Migration` plus a version bump; the DB is built with `allowMainThreadQueries()`.
- Clipboard content and user phrases are stored in this database; most behavioral settings live in default `SharedPreferences`, synced to device-encrypted storage when the settings screen stops (API 24+).

### User dictionary

- `user_word` (entity `UserWord`, `word`+`code` primary key) holds words the user added or corrected during input. It is separate from `phrase` (常用语) on purpose: phrases are sentence templates shown in the keyboard's phrase panel, user words are only injected as top candidates on an exact code match.
- `UserDictManager` is the single read/write entry: it keeps an in-memory `code -> words` index (loaded on a background thread by `Launcher`) because `CustomEngine.processPhrase()` runs on every keystroke and would otherwise hit Room on the main thread. All writes must go through it so the index stays in sync.
- Learning is captured in `RimeEngine.selectCandidate()` / `flushLearning()`: the first selection's `compositionText` is the code, and a record is only written when at least one non-top candidate was chosen (2–6 CJK chars, debounced, capped at 20k entries).
- Injection reuses the existing `📋` (`RimeEngine.CUSTOM_TAG`) candidate channel, so `customPhraseSize` index-offset logic stays untouched. Management UI: `ui/fragment/UserDictFragment.kt` (settings → 输入法 → 用户词库), text import/export via `utils/UserDictCodec.kt` (TSV).

### Native engine constraints

`Rime.kt` loads `libwhaleime.so` from `whalesdk/libs/<abi>`. The precompiled library is closed-source and, per `whalesdk/README.md`, validates the host application package name in unlicensed builds. Changing `applicationId`/package name will cause engine initialization failures unless a licensed library is supplied. Do not replace the `.so` files as part of normal app changes.

## Package conventions

- `com.whale` — application shell only.
- `com.whale.imemodule` — SDK Android layer; resource IDs come from `com.whale.imemodule.R`, even when running inside the `app` module.
- `com.whale.inputmethod` — engine-facing code; UI classes should not touch `Rime` JNI directly.
