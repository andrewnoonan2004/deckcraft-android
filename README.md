# DeckCraft for Android

A native Android port of **[DeckCraft](https://github.com/storytold/deckcraft)** with **full desktop-like control**, designed for Android Laptop, tablets, Chromebooks, and foldable devices.

DeckCraft on Android embeds the genuine desktop presentation engine and egui-based desktop workbench directly inside a hardware-accelerated OpenGL ES 3.0 surface, paired with full hardware mouse, keyboard, and stylus input support.

---

## Key Features

- **Full Desktop Workbench & Engine**:
  - The complete desktop interface (`deckcraft-ui-egui`) and multi-threaded Rust presentation engine (`deckcraft-engine`) compiled for 64-bit Android (`arm64-v8a` and `x86_64`).
  - 16KB ELF page-aligned shared libraries compliant with Android 15 and Android 16.
  - Hardware-accelerated OpenGL ES 3.0 rendering (`egui_glow`) at 60/120 Hz.

- **Desktop-Grade Pointer & Mouse Controls**:
  - Hardware mouse hover (`ACTION_HOVER_MOVE`) with dynamic OS cursor shape changes (pointing hand, text I-beam, grab, resize, crosshair, etc.).
  - Left, right, and middle mouse button support, including context menus on right-click.
  - Stylus barrel-button right-click and two-finger tap right-click for touchscreens.
  - Smooth vertical & horizontal scrolling via mouse wheel and trackpad, plus pinch-to-zoom / `Ctrl + Scroll` canvas zooming.

- **Desktop Keyboard & Shortcuts**:
  - Full hardware keyboard mapping for all desktop presentation shortcuts:
    - `Ctrl + O`: Open presentation via Android Scoped Storage (SAF)
    - `Ctrl + S` / `Ctrl + Shift + S`: In-place save and Save As
    - `Ctrl + K`: Command Palette
    - `F5`: Start Slide Show presentation mode
    - `Ctrl + M` or `+ Slide`: Insert new slide
    - `Ctrl + F`: Search & Find
    - `Ctrl + Z` / `Ctrl + Y` (or `Ctrl + Shift + Z`): Undo / Redo
    - `F1`–`F12`, arrows, `Delete`, `Escape`, `Tab`, etc.
  - Android IME soft keyboard integration for slide text editing and speaker notes.

- **Floating Desktop Control Bar (Material 3 Expressive)**:
  - A collapsible Compose overlay providing one-tap access to sticky `Ctrl`, `Shift`, `Alt`, and `Right-Click` modifiers for touch and stylus usage.
  - Quick action buttons for Command Palette, Open, Save, Save As, + Slide, Present (`F5`), Undo, Redo, Find, Print, Share, Keyboard toggle, and UI scaling (75%–200%).
  - Warm terracotta/tangerine expressive palette with dynamic system color adapting.

- **Android System Integration**:
  - **Scoped Storage (SAF)**: `ACTION_OPEN_DOCUMENT`, `ACTION_OPEN_MULTIPLE_DOCUMENTS`, and `ACTION_CREATE_DOCUMENT` wired into DeckCraft's asynchronous file pickers for PowerPoint (`.pptx`, `.potx`, `.ppsx`), DeckCraft bundles (`.deckcraft`), and PDFs.
  - **Bi-directional SAF Write-Back**: Saving a presentation (`Ctrl+S`) writes back directly to the source `content://` URI without creating duplicate files.
  - **Intents**: Open and view presentations directly from file managers, email, and share sheets (`ACTION_VIEW`, `ACTION_EDIT`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
  - **System Printing**: Native `PrintManager` integration receiving DeckCraft's composed PDF print jobs.
  - **Window Resizing**: Full freeform multi-window resizing support (`android:resizeableActivity="true"`).

- **Reliability & Crash Safety**:
  - Built-in [`CrashReporter`](app/src/main/kotlin/com/andrewnoonan/deckcraft/platform/CrashReporter.kt) capturing both JVM uncaught exceptions and Rust native panics.
  - Dedicated copyable crash screen for quick diagnostic reporting without needing `adb logcat`.

- **Continuous Upstream Synchronization**:
  - Automated weekly and on-demand GitHub Action workflow (`.github/workflows/check-upstream-sync.yml`) tracking [storytold/deckcraft](https://github.com/storytold/deckcraft).
  - Sync script (`scripts/sync-upstream.sh`) that checks revisions, incorporates upstream updates into `deckcraft/`, runs test suites, and opens pull requests automatically.

---

## Project Structure

```
.
├── app/                  # Android application (Kotlin, Jetpack Compose, GLSurfaceView)
│   ├── src/main/         # Android manifest, UI components, bridge interfaces, storage managers
│   └── src/test/         # Unit, Robolectric (SDK 34), and Compose UI tests
├── rust-bridge/          # JNI + OpenGL ES 3.0 bridge connecting Kotlin to DeckCraft
│   ├── src/lib.rs        # JNI exports, glow painter, and event translators
│   └── shims/            # Android platform shims for arboard (clipboard)
├── deckcraft/            # DeckCraft source tree (models, engine, presentation logic, ui)
├── scripts/
│   ├── build-android-rust.sh  # Cross-compilation script for aarch64 and x86_64
│   └── sync-upstream.sh       # Upstream synchronization and check tool
└── .github/workflows/
    ├── check-upstream-sync.yml # Weekly upstream sync workflow
    ├── build-release-apk.yml   # Release APK build & artifact publishing
    └── play-store-release.yml  # Google Play App Bundle (.aab) release workflow
```

---

## Building from Source

### Prerequisites

1. **Android SDK & NDK**:
   - `compileSdk = 36`, `minSdk = 28`
   - Android NDK `r27` or newer (e.g., `29.0.14206865`) with LLVM toolchain.
2. **Rust & Cargo**:
   - Rust stable (1.80+)
   - Targets installed via rustup:
     ```bash
     rustup target add aarch64-linux-android x86_64-linux-android
     ```
3. **JDK**:
   - Java 17

### 1. Build Native Rust Bridge (`libdeckcraft_android.so`)

```bash
bash scripts/build-android-rust.sh
```

This compiles optimized 16KB-page-aligned `.so` libraries into `app/src/main/jniLibs/arm64-v8a` and `app/src/main/jniLibs/x86_64`.

### 2. Run Tests

```bash
./gradlew testDebugUnitTest
```

### 3. Build Signed Debug / Release APKs

```bash
./gradlew assembleDebug assembleRelease bundleRelease
```

The outputs are located in:
- `app/build/outputs/apk/debug/DeckCraft-debug.apk`
- `app/build/outputs/apk/release/DeckCraft-release.apk`
- `app/build/outputs/bundle/release/DeckCraft-release.aab`

---

## Upstream Synchronization

To check if upstream [storytold/deckcraft](https://github.com/storytold/deckcraft) has new commits:

```bash
bash scripts/sync-upstream.sh --check
```

To incorporate upstream updates:

```bash
bash scripts/sync-upstream.sh --sync
```

---

## License

DeckCraft for Android is licensed under the Apache 2.0 License, consistent with upstream [DeckCraft](https://github.com/storytold/deckcraft).
