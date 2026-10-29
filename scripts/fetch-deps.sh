#!/usr/bin/env bash
# Clones the native dependencies into ../third_party (next to this repo) at the commits OmniChat was built with.
# Optional extras: --opencl builds the Khronos OpenCL ICD loader (enables the OpenCL GPU backend),
# --hexagon downloads the Hexagon SDK Community Edition (enables the Snapdragon NPU backend).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
tp="$here/../third_party"
mkdir -p "$tp"

clone() { # name url commit
  if [ ! -d "$tp/$1/.git" ]; then git clone --filter=blob:none "$2" "$tp/$1"; fi
  git -C "$tp/$1" fetch --quiet origin "$3" 2>/dev/null || true
  git -C "$tp/$1" checkout --quiet "$3"
  echo "$1 @ $3"
}
clone llama.cpp        https://github.com/ggml-org/llama.cpp              338fce6511c744893cc0d3d958b275f40552c7a4
clone Vulkan-Headers   https://github.com/KhronosGroup/Vulkan-Headers     73814d006638aa00bd553215b054f0f02e457e22
clone SPIRV-Headers    https://github.com/KhronosGroup/SPIRV-Headers      fbf86836a9b1966586faf7be7bb8a6440e156d62
clone OpenCL-Headers   https://github.com/KhronosGroup/OpenCL-Headers     82f60316cc142e7b21dc1d8983b26de18858fbd9

for arg in "$@"; do
  case "$arg" in
    --opencl)
      clone OpenCL-ICD-Loader https://github.com/KhronosGroup/OpenCL-ICD-Loader 007c8e892f52e9b9f288830cbce30ab2f97760d0
      ndk="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/30.0.16248370}"
      cmake -S "$tp/OpenCL-ICD-Loader" -B "$tp/opencl-android/build" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=28 \
        -DCMAKE_BUILD_TYPE=Release -DOPENCL_ICD_LOADER_HEADERS_DIR="$tp/OpenCL-Headers" -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON
      cmake --build "$tp/opencl-android/build" --target OpenCL -j
      mkdir -p "$tp/opencl-android/lib"
      cp "$tp/opencl-android/build/libOpenCL.so" "$tp/opencl-android/lib/"
      echo "OpenCL loader built (link-time only; the phone's own libOpenCL.so is used at runtime)"
      ;;
    --hexagon)
      mkdir -p "$tp/hexagon"
      gh release download v6.7.0.0 -R snapdragon-toolchain/hexagon-sdk -p 'hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz' -D "$tp/hexagon" --clobber
      tar xJf "$tp/hexagon/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz" -C "$tp/hexagon"
      [ -d "$tp/hexagon/6.7.0.0" ] || echo "WARNING: expected $tp/hexagon/6.7.0.0; move the extracted SDK folder there"
      ;;
  esac
done
