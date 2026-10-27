#!/usr/bin/env bash
# Cross-compiles ottjni for Android into OUT_DIR/<abi>/libottjni.so.
#   ANDROID_NDK_HOME=... rust/build-android.sh OUT_DIR abi...
# Needs cargo, cmake, ninja and libclang (LIBCLANG_PATH) on the host.
#
# Unicorn, which runs Apple's x86_64 CoreADI, is C built by cmake. Three
# things make it link for Android:
#  - Android has no libpthread (it is part of libc), but unicorn links
#    -lpthread, so it gets an empty one.
#  - Unicorn's code generator flushes the instruction cache through
#    __clear_cache, which rustc leaves out with -nodefaultlibs; it comes from
#    the NDK's compiler-rt builtins.
#  - bindgen parses unicorn's headers against the NDK sysroot, not the host.
# Unicorn picks the CPU it builds for from ANDROID_ABI, which only the NDK's
# own cmake toolchain file defines; without it unicorn asks the compiler for
# its predefined macros, and the NDK's plain clang reports the host's x86_64.
# So cmake gets a toolchain file that sets the ABI and includes the NDK's.
# Segments are 16 KB aligned, as Android 15 devices with 16 KB pages require.
set -euo pipefail
out=$(realpath -m "$1"); shift
here=$(cd "$(dirname "$0")" && pwd)
ndk=${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK}
api=24
tc=$ndk/toolchains/llvm/prebuilt/linux-x86_64
# unicorn's cmake build finds the NDK under these names.
export ANDROID_NDK=$ndk ANDROID_NDK_ROOT=$ndk

cd "$here"
for abi in "$@"; do
  case $abi in
    arm64-v8a) triple=aarch64-linux-android; arch=aarch64 ;;
    x86_64) triple=x86_64-linux-android; arch=x86_64 ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  rt=${triple//-/_}
  var=$(echo "$rt" | tr 'a-z' 'A-Z')
  stub=$here/target/android-stub/$abi
  mkdir -p "$stub"
  : > "$stub/empty.c"
  "$tc/bin/clang" -c "$stub/empty.c" -o "$stub/empty.o" --target="$triple$api"
  "$tc/bin/llvm-ar" rc "$stub/libpthread.a" "$stub/empty.o"
  toolchain=$stub/toolchain.cmake
  printf 'set(ANDROID_ABI %s)\nset(ANDROID_PLATFORM android-%s)\ninclude(%s/build/cmake/android.toolchain.cmake)\n' \
    "$abi" "$api" "$ndk" > "$toolchain"
  builtins=$(ls "$tc"/lib/clang/*/lib/linux/libclang_rt.builtins-$arch-android.a | head -1)
  (
    export "CC_$rt=$tc/bin/$triple$api-clang" "CXX_$rt=$tc/bin/$triple$api-clang++" "AR_$rt=$tc/bin/llvm-ar"
    export "CFLAGS_$rt=-L$stub" "CXXFLAGS_$rt=-L$stub"
    export "CARGO_TARGET_${var}_LINKER=$tc/bin/$triple$api-clang"
    export "CARGO_TARGET_${var}_RUSTFLAGS=-L native=$stub -C link-arg=$builtins -C link-arg=-Wl,-z,max-page-size=16384"
    export "CMAKE_TOOLCHAIN_FILE_$rt=$toolchain"
    export "BINDGEN_EXTRA_CLANG_ARGS_$rt=--sysroot=$tc/sysroot"
    cargo build --release -p ottjni --target "$triple"
  )
  mkdir -p "$out/$abi"
  cp "target/$triple/release/libottjni.so" "$out/$abi/"
done
