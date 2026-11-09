#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PROJECT_HASH="$(printf '%s' "$PROJECT_ROOT" | sha1sum | cut -c1-8)"

export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$HOME/Android/Sdk/cmake/3.31.6/bin:$PATH"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$HOME/.cache/projects/Deckcraft-$PROJECT_HASH/cargo}"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
  if [ -d "$ANDROID_SDK_ROOT/ndk/29.0.14206865" ]; then
    export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
  elif [ -d "$ANDROID_SDK_ROOT/ndk" ] && [ -n "$(ls -A "$ANDROID_SDK_ROOT/ndk" 2>/dev/null)" ]; then
    export ANDROID_NDK_ROOT="$(ls -d "$ANDROID_SDK_ROOT/ndk"/* | sort -V | tail -n 1)"
  elif [ -n "${ANDROID_NDK_HOME:-}" ]; then
    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
  else
    export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
  fi
fi
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

export PATH="$TOOLCHAIN/bin:$PATH"

# 64-bit ARM (arm64-v8a)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android28-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android28-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android28-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

# 64-bit x86_64 (Android Laptop / Chromebook / Emulator)
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/x86_64-linux-android28-clang"
export CC_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android28-clang"
export CXX_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android28-clang++"
export AR_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RANLIB_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

echo "==> Cross-compiling libdeckcraft_android.so for aarch64-linux-android (arm64-v8a)..."
cargo build \
  --manifest-path "$PROJECT_ROOT/rust-bridge/Cargo.toml" \
  --target aarch64-linux-android \
  --release

echo "==> Cross-compiling libdeckcraft_android.so for x86_64-linux-android (x86_64)..."
cargo build \
  --manifest-path "$PROJECT_ROOT/rust-bridge/Cargo.toml" \
  --target x86_64-linux-android \
  --release

ARM64_OUT="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
X86_64_OUT="$PROJECT_ROOT/app/src/main/jniLibs/x86_64"
mkdir -p "$ARM64_OUT" "$X86_64_OUT"

cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libdeckcraft_android.so" "$ARM64_OUT/libdeckcraft_android.so"
cp "$CARGO_TARGET_DIR/x86_64-linux-android/release/libdeckcraft_android.so" "$X86_64_OUT/libdeckcraft_android.so"

"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$ARM64_OUT/libdeckcraft_android.so"
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$X86_64_OUT/libdeckcraft_android.so"

echo "==> Verifying 64-bit ELF binaries and 16KB LOAD alignment..."
ls -lh "$ARM64_OUT/libdeckcraft_android.so" "$X86_64_OUT/libdeckcraft_android.so"
"$TOOLCHAIN/bin/llvm-readelf" -l "$ARM64_OUT/libdeckcraft_android.so" | grep LOAD
"$TOOLCHAIN/bin/llvm-readelf" -l "$X86_64_OUT/libdeckcraft_android.so" | grep LOAD
"$TOOLCHAIN/bin/llvm-nm" -D "$ARM64_OUT/libdeckcraft_android.so" | grep Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge | head -n 25

# The library is linked BIND_NOW: any strong undefined symbol the platform libraries don't export
# makes System.loadLibrary throw and the app crash on launch. Weak undefined symbols are fine.
check_unresolved() {
  local so="$1" triple="$2"
  local sysroot_libs="$TOOLCHAIN/sysroot/usr/lib/$triple/28"
  local missing
  missing="$(comm -23 \
    <("$TOOLCHAIN/bin/llvm-readelf" --dyn-syms --wide "$so" | awk '$5 == "GLOBAL" && $7 == "UND" {print $8}' | sed 's/@.*//' | sort -u) \
    <(for lib in "$sysroot_libs"/*.so; do "$TOOLCHAIN/bin/llvm-nm" -D --defined-only "$lib" 2>/dev/null | awk '{print $3}'; done | sed 's/@.*//' | sort -u))"
  if [ -n "$missing" ]; then
    echo "ERROR: $so has unresolved strong symbols (dlopen would fail):" >&2
    echo "$missing" >&2
    exit 1
  fi
  echo "    $triple: no unresolved strong symbols"
}
echo "==> Checking for symbols dlopen cannot resolve..."
check_unresolved "$ARM64_OUT/libdeckcraft_android.so" aarch64-linux-android
check_unresolved "$X86_64_OUT/libdeckcraft_android.so" x86_64-linux-android
echo "==> Done!"
