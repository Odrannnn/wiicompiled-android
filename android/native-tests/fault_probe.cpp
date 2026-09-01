#include <signal.h>
#include <setjmp.h>
#include <sys/mman.h>
#include <ucontext.h>
#include <unistd.h>
#include <cstdio>
#include <cstdint>
#include "host_fault_access.h"
static sigjmp_buf recovery;
static volatile sig_atomic_t accessType;
static void* expectedAddress;
static void fault(int, siginfo_t* info, void* raw) {
    if (!info || info->si_addr != expectedAddress || !raw) _exit(2);
    auto* uc = static_cast<ucontext_t*>(raw);
    accessType = static_cast<sig_atomic_t>(HostFault::DecodeAarch64Context(
        uc->uc_mcontext.__reserved, sizeof(uc->uc_mcontext.__reserved)));
    siglongjmp(recovery, 1);
}
int main() {
    struct sigaction action{};
    action.sa_sigaction = fault;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSEGV, &action, nullptr)) return 2;
    const size_t page = sysconf(_SC_PAGESIZE);
    void* memory = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (memory == MAP_FAILED) return 2;
    expectedAddress = memory;
    if (sigsetjmp(recovery, 1) == 0) {
        volatile uint8_t read = *static_cast<volatile uint8_t*>(memory);
        (void)read;
        return 3;
    }
    bool ok = accessType == static_cast<sig_atomic_t>(HostFault::Access::Read);
    if (mprotect(memory, page, PROT_READ)) return 2;
    if (sigsetjmp(recovery, 1) == 0) {
        *static_cast<volatile uint8_t*>(memory) = 1;
        return 3;
    }
    ok = ok && accessType == static_cast<sig_atomic_t>(HostFault::Access::Write);
    munmap(memory, page);
    uint32_t malformed[4] = {0x45535201, 4096, 0, 0};
    ok = ok && HostFault::DecodeAarch64Context(malformed, sizeof(malformed)) == HostFault::Access::Unknown;
    uint64_t wrongClass[2] = {0x0000001045535201ull, 0};
    ok = ok && HostFault::DecodeAarch64Context(wrongClass, sizeof(wrongClass)) == HostFault::Access::Unknown;
    std::puts(ok ? "PASS: real ARM64 read/write faults and malformed ESR rejection" : "FAIL: ARM64 fault decoder");
    return ok ? 0 : 1;
}
