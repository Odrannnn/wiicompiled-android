#!/bin/sh
set -eu
: "${LLVM_VERSION:?}" "${ANDROID_NDK_VERSION:?}"
archive="/src/.tools/llvm-project-${LLVM_VERSION}.src.tar.xz"
source_dir="/work/llvm-project-${LLVM_VERSION}.src"
host_build="/work/llvm-host-${LLVM_VERSION}"
target_build="/work/llvm-android-${LLVM_VERSION}"
install_dir="/out/toolchain"
ndk="/opt/android-studio/ndk/${ANDROID_NDK_VERSION}"
if [ ! -d "$source_dir/llvm" ]; then
  rm -rf "$source_dir"
  mkdir -p "$source_dir"
  tar -xJf "$archive" -C "$source_dir" --strip-components=1
fi
cmake -S "$source_dir/llvm" -B "$host_build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DLLVM_ENABLE_PROJECTS=clang \
  -DLLVM_TARGETS_TO_BUILD=AArch64 -DLLVM_INCLUDE_TESTS=OFF \
  -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_ENABLE_TERMINFO=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DCLANG_INCLUDE_TESTS=OFF
cmake --build "$host_build" --target llvm-tblgen clang-tblgen -j "${BUILD_JOBS:-8}"
cmake -S "$source_dir/llvm" -B "$target_build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_INSTALL_PREFIX="$install_dir" \
  -DLLVM_NATIVE_TOOL_DIR="$host_build/bin" -DLLVM_ENABLE_PROJECTS="clang;lld" \
  -DLLVM_TARGETS_TO_BUILD=AArch64 -DLLVM_DEFAULT_TARGET_TRIPLE=aarch64-linux-android30 \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_INCLUDE_DOCS=OFF -DLLVM_BUILD_DOCS=OFF -DLLVM_ENABLE_BINDINGS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_RTTI=ON \
  -DLLVM_BUILD_LLVM_DYLIB=OFF -DLLVM_LINK_LLVM_DYLIB=OFF \
  -DCLANG_INCLUDE_TESTS=OFF -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF
cmake --build "$target_build" --target clang lld llvm-ar -j "${BUILD_JOBS:-8}"
rm -rf "$install_dir"
mkdir -p "$install_dir/bin" "$install_dir/lib/clang/${LLVM_VERSION%%.*}"
cp "$target_build/bin/clang" "$install_dir/bin/clang"
cp "$target_build/bin/lld" "$install_dir/bin/lld"
cp "$target_build/bin/llvm-ar" "$install_dir/bin/llvm-ar"
strip_tool="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$strip_tool" --strip-unneeded "$install_dir/bin/clang" "$install_dir/bin/lld" "$install_dir/bin/llvm-ar"
mkdir -p "/out/jniLibs/arm64-v8a"
cp "$install_dir/bin/clang" "/out/jniLibs/arm64-v8a/libwiicompiled_clang.so"
cp "$install_dir/bin/lld" "/out/jniLibs/arm64-v8a/libwiicompiled_lld.so"
cp "$install_dir/bin/llvm-ar" "/out/jniLibs/arm64-v8a/libwiicompiled_llvm_ar.so"
# Keep the SDK in one archive. The NDK has Linux headers whose names differ
# only by case, so expanding it onto a Windows checkout would lose files.
sdk_stage="/work/compiler-sdk-${LLVM_VERSION}"
rm -rf "$sdk_stage"
mkdir -p "$sdk_stage/toolchain/lib/clang/${LLVM_VERSION%%.*}/lib/linux" "/out/assets"
cp -R "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot" "$sdk_stage/toolchain/sysroot"
cp -R "$target_build/lib/clang/${LLVM_VERSION%%.*}/include" \
  "$sdk_stage/toolchain/lib/clang/${LLVM_VERSION%%.*}/include"
cp "$ndk"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/libclang_rt.builtins-aarch64-android.a \
  "$sdk_stage/toolchain/lib/clang/${LLVM_VERSION%%.*}/lib/linux/"
rm -f "/out/assets/compiler-sdk.zip"
(cd "$sdk_stage" && cmake -E tar cf "/out/assets/compiler-sdk.zip" --format=zip toolchain)
