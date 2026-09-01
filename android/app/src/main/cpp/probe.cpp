#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <thread>
#include <mutex>
#include "guest_flat_memory.h"
#include "isa/ppc_isa_float.h"
#include "libco.h"

namespace {
thread_local cothread_t scheduler;
thread_local unsigned switches;
thread_local bool fiberFpOk;

void FiberEntry() {
    const uint32_t saved = MkwGetHostFpControl();
    // Use a different rounding mode so context restoration is actually tested.
    const uint32_t changed = (saved & ~(3u << 22)) | (1u << 22);
    MkwSetHostFpControl(changed);
    for (;;) {
        ++switches;
        co_switch(scheduler);
        fiberFpOk = fiberFpOk && MkwGetHostFpControl() == changed;
    }
}

bool TestFibers() {
    scheduler = co_active();
    switches = 0;
    fiberFpOk = true;
    const uint32_t saved = MkwGetHostFpControl();
    cothread_t fiber = co_create(256 * 1024, FiberEntry);
    if (!fiber) return false;
    bool ok = true;
    for (unsigned i = 0; i < 10000; ++i) {
        co_switch(fiber);
        ok = ok && MkwGetHostFpControl() == saved;
    }
    co_delete(fiber);
    return ok && fiberFpOk && switches == 10000;
}

bool TestArithmetic() {
    const uint32_t saved = MkwGetHostFpControl();
    const bool savedNi = g_mkwHostNiActive;
    const double savedThreshold = g_mkwNiFlushThreshold;
    MkwApplyHostNiMode(0);
    // Exact values and signed zero catch paired-lane order/sign mistakes.
    volatile float input0 = 1.25f, input1 = -2.5f;
    const double a = PpcPackPairedInline(input0, input1);
    const double b = PpcPackPairedInline(2.0f, 0.5f);
    const double sum = PPC_PsAddInline(a, b);
    const double zero = PpcPackPairedInline(-0.0f, 0.0f);
    bool ok = PpcGetPs0Inline(sum) == 3.25f && PpcGetPs1Inline(sum) == -2.0f
        && PpcBitCastToU32Inline(PpcGetPs0Inline(zero)) == 0x80000000u
        && PpcBitCastToU32Inline(PpcGetPs1Inline(zero)) == 0;
    MkwApplyHostNiMode(4);
    ok = ok && (MkwGetHostFpControl() & kMkwFpControlFlushToZeroBits) != 0;
    MkwSetHostFpControl(saved);
    g_mkwHostNiActive = savedNi;
    g_mkwNiFlushThreshold = savedThreshold;
    return ok;
}

std::string TestMapping(long pageSize) {
    if (pageSize <= 0) return "FAIL: cannot read host page size";
    const size_t length = GuestFlat::kGuestSpaceSize + 0x10000;
    void* requested = reinterpret_cast<void*>(GuestFlat::kFixedFlatGuestBase);
    void* reserved = mmap(requested, length, PROT_NONE,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (reserved == MAP_FAILED) return std::string("FAIL: reservation: ") + strerror(errno);
    if (reserved != requested) {
        munmap(reserved, length);
        return "FAIL: 64 GiB fixed base unavailable (nothing overwritten)";
    }
    // ASharedMemory works within Android's app sandbox. No /dev/shm or disk file.
    const int fd = ASharedMemory_create("wiicompiled-probe", pageSize);
    if (fd < 0) {
        munmap(reserved, length);
        return std::string("FAIL: shared memory: ") + strerror(errno);
    }
    void* host = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    void* guest = mmap(reserved, pageSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
    bool ok = host != MAP_FAILED && guest != MAP_FAILED;
    if (ok) {
        auto* h = static_cast<volatile uint32_t*>(host);
        auto* g = static_cast<volatile uint32_t*>(guest);
        *h = 0x12345678;
        ok = *g == 0x12345678;
        *g = 0xaabbccdd;
        ok = ok && *h == 0xaabbccdd;
        ok = ok && mprotect(guest, pageSize, PROT_NONE) == 0;
        *h = 0xdeadbeef; // Host alias remains writable when guest alias is protected.
        ok = ok && mprotect(guest, pageSize, PROT_READ) == 0 && *g == 0xdeadbeef;
    }
    if (host != MAP_FAILED) munmap(host, pageSize);
    close(fd);
    munmap(reserved, length);
    return ok ? "PASS: fixed 4 GiB reservation, shared aliases and page protection"
              : "FAIL: mapping/alias/page protection checks";
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_wiicompiled_portlab_NativeProbe_configureAndroidPaths(
    JNIEnv* env, jclass, jstring internalPath, jstring externalPath) {
    if (!internalPath || !externalPath) return;
    const char* internal = env->GetStringUTFChars(internalPath, nullptr);
    const char* external = env->GetStringUTFChars(externalPath, nullptr);
    if (internal && external) {
        setenv("MKW_ANDROID_INTERNAL_STORAGE", internal, 1);
        setenv("MKW_ANDROID_EXTERNAL_STORAGE", external, 1);
    }
    if (external) env->ReleaseStringUTFChars(externalPath, external);
    if (internal) env->ReleaseStringUTFChars(internalPath, internal);
}

namespace {
void SetOptionalEnv(JNIEnv* env, const char* name, jstring value) {
    if (!value) { unsetenv(name); return; }
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text) {
        if (*text) setenv(name, text, 1); else unsetenv(name);
        env->ReleaseStringUTFChars(value, text);
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_wiicompiled_portlab_NativeProbe_configureCustomVulkanDriver(
    JNIEnv* env, jclass, jstring directory, jstring soname, jstring label,
    jstring nativeLibraryDirectory, jstring temporaryDirectory, jstring filesDirectory) {
    SetOptionalEnv(env, "MKW_VULKAN_DRIVER_DIR", directory);
    SetOptionalEnv(env, "MKW_VULKAN_DRIVER_SONAME", soname);
    SetOptionalEnv(env, "MKW_VULKAN_DRIVER_LABEL", label);
    SetOptionalEnv(env, "MKW_ANDROID_NATIVE_LIBRARY_DIR", nativeLibraryDirectory);
    SetOptionalEnv(env, "MKW_VULKAN_DRIVER_TMP", temporaryDirectory);
    SetOptionalEnv(env, "MKW_ANDROID_FILES_DIR", filesDirectory);
}

extern "C" JNIEXPORT void JNICALL
Java_org_wiicompiled_portlab_NativeProbe_configureModOverlays(
    JNIEnv* env, jclass, jstring overlayRoots) {
    SetOptionalEnv(env, "MKW_ANDROID_OVERLAY_ROOTS", overlayRoots);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_wiicompiled_portlab_NativeProbe_run(JNIEnv* env, jclass) {
    static std::mutex probeMutex;
    std::lock_guard<std::mutex> lock(probeMutex);
    try {
        std::ostringstream out;
        const long pageSize = sysconf(_SC_PAGESIZE);
        out << "ARM64 native runtime checks\n\nHost page size: " << pageSize << " bytes\n";
        out << (pageSize == 4096 ? "PASS: upstream 4 KB memory-page assumption\n"
                                : "BLOCKED: full runtime still assumes 4 KB pages\n");
        out << (TestArithmetic() ? "PASS" : "FAIL") << ": upstream paired-single/FPCR smoke checks\n";
        bool first = false, second = false;
        // Concurrent OS threads exercise separate active contexts and scheduler handles.
        std::thread t1([&] { first = TestFibers(); });
        std::thread t2([&] { second = TestFibers(); });
        t1.join(); t2.join();
        out << (first && second ? "PASS" : "FAIL")
            << ": 20,000 coroutine yields across two threads + FPCR restore\n";
        out << TestMapping(pageSize) << "\n\n";
        out << "These are platform smoke tests, not a game or physics accuracy test.\n"
            << "Not tested here: Vulkan/Aurora, audio, input, guest faults, game boot.";
        return env->NewStringUTF(out.str().c_str());
    } catch (const std::exception& error) {
        return env->NewStringUTF((std::string("FAIL: native exception: ") + error.what()).c_str());
    }
}

