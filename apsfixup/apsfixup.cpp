// libapsfixup.so — runtime fix for the dodge APS turbo P010 chroma / green-frame bug.
//
// Root cause: the port's gralloc reports a wrong plane layout for the 12.5MP P010
// capture output, so ArcSoft/Algo compute a garbage chroma pointer, a zero chroma
// stride, and run p010LSB2MSBNeon with an uninitialized source stride.
//
// Corrections (same arithmetic as docs/frida/op_chroma_repair.js):
//   (1) At every ARC_Turbo_*_Process entry: plane[1] = luma + Ysize (2/3 of the
//       mapping), pitch[1] = pitch[0].
//   (2) p010LSB2MSBNeon: set w5 so w4*w5*1.5 == buffer (full Y+UV).
//
// GOT redirection only -- no code patching, no execmem/execmod.
//
// Durable across firmware bumps:
//   * JUMP_SLOT offsets are discovered by walking the in-memory ELF (dynsym +
//     JMPREL). No pinned BuildId / P010_FUNC_OFF / DLSYM_GOT_OFF.
//   * wrap_dlsym interposes every ARC_Turbo_*_Process (RAW, HDR, and the Bokeh
//     variants). A.01+ routes stills through TURBOHDR; hooking only RAW left
//     those frames uncorrected (OpenCL then ION-failed and the JPEG was green).
//   * A first-try GOT miss is retried, not latched as "blob drift". BIND_NOW
//     may not have filled the slot yet when our constructor runs.
//
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <inttypes.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "apsfixup"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

static const uint64_t MIN_SNAPSHOT = 0x400000;

static bool range_of(uint64_t addr, uint64_t* out_base, uint64_t* out_size) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    char buf[8192];
    ssize_t bytes;
    uint64_t lo = 0, hi = 0;
    int state = 0;

    while ((bytes = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < bytes; i++) {
            char c = buf[i];
            if (state == 0) {
                if (c == '-') state = 1;
                else lo = (lo << 4) | (c <= '9' ? c - '0' : (c & 0xDF) - 'A' + 10);
            } else if (state == 1) {
                if (c == ' ') {
                    if (addr >= lo && addr < hi) {
                        *out_base = lo;
                        *out_size = hi - lo;
                        close(fd);
                        return true;
                    }
                    state = 2;
                } else {
                    hi = (hi << 4) | (c <= '9' ? c - '0' : (c & 0xDF) - 'A' + 10);
                }
            } else if (state == 2) {
                if (c == '\n') {
                    state = 0;
                    lo = 0;
                    hi = 0;
                }
            }
        }
    }
    close(fd);
    return false;
}

static bool module_base(const char* name, uint64_t* out_base) {
    FILE* f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[512];
    uint64_t best = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, name)) {
            uint64_t lo;
            if (sscanf(line, "%" SCNx64, &lo) == 1)
                if (best == 0 || lo < best) best = lo;
        }
    }
    fclose(f);
    if (best) {
        *out_base = best;
        return true;
    }
    return false;
}

static bool got_redirect(uint64_t slot, void* newval, void** old) {
    void** got = (void**)slot;
    uintptr_t page = slot & ~(uintptr_t)0xfff;
    if (mprotect((void*)page, 0x1000, PROT_READ | PROT_WRITE) != 0) {
        LOGW("mprotect GOT %p failed", (void*)slot);
        return false;
    }
    if (old) *old = *got;
    *got = newval;
    mprotect((void*)page, 0x1000, PROT_READ);
    return true;
}

static bool plane_ok(uint64_t v, uint64_t* base, uint64_t* avail) {
    uint64_t b, s;
    if (!v || !range_of(v, &b, &s)) return false;
    uint64_t a = (b + s) - v;
    if (a < MIN_SNAPSHOT) return false;
    if (base) *base = b;
    if (avail) *avail = a;
    return true;
}

static int g_dumped = 0;
static void dump_struct_once(uint8_t* b, uint64_t lim) {
    if (g_dumped >= 3) return;
    g_dumped++;
    for (uint64_t off = 0; off < 0x80 && (uint64_t)(b + off + 8) <= lim; off += 8) {
        uint64_t v = *(uint64_t*)(b + off), vb, vs;
        if (range_of(v, &vb, &vs))
            LOGI("struct +0x%02x = 0x%016llx  [mapped base=0x%llx size=0x%llx avail=0x%llx]",
                 (unsigned)off, (unsigned long long)v, (unsigned long long)vb,
                 (unsigned long long)vs, (unsigned long long)((vb + vs) - v));
        else
            LOGI("struct +0x%02x = 0x%016llx", (unsigned)off, (unsigned long long)v);
    }
}

static void repair_struct(void* p) {
    if (!p) return;
    uint64_t mb, ms;
    if (!range_of((uint64_t)p, &mb, &ms)) return;
    uint8_t* b = (uint8_t*)p;
    uint64_t lim = mb + ms;
    dump_struct_once(b, lim);
    for (int off = 0; off + 16 <= 0x80 && (uint64_t)(b + off + 16) <= lim; off += 8) {
        uint64_t luma = *(uint64_t*)(b + off), chroma = *(uint64_t*)(b + off + 8);
        uint64_t lb, avail;
        if (!plane_ok(luma, &lb, &avail)) continue;
        uint64_t cb, cs;
        if (chroma && range_of(chroma, &cb, &cs) && cb == lb) continue;
        uint64_t ysize = (avail * 2 / 3) & ~0xfffULL;
        *(uint64_t*)(b + off + 8) = luma + ysize;
        uint32_t yp = 0;
        if ((uint64_t)(b + off + 0x28) <= lim) {
            yp = *(uint32_t*)(b + off + 0x20);
            if (yp > 0 && *(uint32_t*)(b + off + 0x24) == 0)
                *(uint32_t*)(b + off + 0x24) = yp;
        }
        LOGI("chroma fix @+0x%x: luma=%p chroma=%p -> %p (avail=0x%llx ysize=0x%llx pitch=%u)",
             off, (void*)luma, (void*)chroma, (void*)(luma + ysize),
             (unsigned long long)avail, (unsigned long long)ysize, yp);
        return;
    }
}

extern "C" __attribute__((visibility("hidden"))) void aps_repair_structs(void* a1, void* a2, void* a3) {
    repair_struct(a1);
    repair_struct(a2);
    repair_struct(a3);
}

// Each ARC_Turbo_*_Process keeps its own real pointer + trampoline so RAW and
// HDR can be live at the same time. The trampoline is naked asm because these
// functions take x0-x7 plus stack args -- a C wrapper would drop the stack.
#define ARC_TRAMP(tag)                                                            \
    extern "C" __attribute__((visibility("hidden"))) void* aps_real_##tag;        \
    extern "C" void wrap_arc_##tag();                                             \
    __asm__(                                                                      \
        "    .text\n"                                                             \
        "    .balign 4\n"                                                         \
        "    .global wrap_arc_" #tag "\n"                                         \
        "    .type wrap_arc_" #tag ", %function\n"                                \
        "wrap_arc_" #tag ":\n"                                                    \
        "    stp x29, x30, [sp, #-0x60]!\n"                                       \
        "    mov x29, sp\n"                                                       \
        "    stp x0, x1, [sp, #0x10]\n"                                           \
        "    stp x2, x3, [sp, #0x20]\n"                                           \
        "    stp x4, x5, [sp, #0x30]\n"                                           \
        "    stp x6, x7, [sp, #0x40]\n"                                           \
        "    str x8, [sp, #0x50]\n"                                               \
        "    ldr x0, [sp, #0x18]\n"                                               \
        "    ldr x1, [sp, #0x20]\n"                                               \
        "    ldr x2, [sp, #0x28]\n"                                               \
        "    bl  aps_repair_structs\n"                                            \
        "    ldp x0, x1, [sp, #0x10]\n"                                           \
        "    ldp x2, x3, [sp, #0x20]\n"                                           \
        "    ldp x4, x5, [sp, #0x30]\n"                                           \
        "    ldp x6, x7, [sp, #0x40]\n"                                           \
        "    ldr x8, [sp, #0x50]\n"                                               \
        "    ldp x29, x30, [sp], #0x60\n"                                         \
        "    adrp x16, aps_real_" #tag "\n"                                       \
        "    add  x16, x16, #:lo12:aps_real_" #tag "\n"                           \
        "    ldr  x16, [x16]\n"                                                   \
        "    br   x16\n");

ARC_TRAMP(raw)
ARC_TRAMP(raw_bokeh)
ARC_TRAMP(hdr)
ARC_TRAMP(hdr_bokeh)

extern "C" __attribute__((visibility("hidden"))) void* aps_real_raw = nullptr;
extern "C" __attribute__((visibility("hidden"))) void* aps_real_raw_bokeh = nullptr;
extern "C" __attribute__((visibility("hidden"))) void* aps_real_hdr = nullptr;
extern "C" __attribute__((visibility("hidden"))) void* aps_real_hdr_bokeh = nullptr;

static bool is_arc_process(const char* s) {
    if (!s) return false;
    if (strncmp(s, "ARC_Turbo_", 10) != 0) return false;
    size_t n = strlen(s);
    if (n < 8 || strcmp(s + n - 8, "_Process") != 0) return false;
    if (strstr(s, "PreProcess") || strstr(s, "PostProcess") || strstr(s, "preProcess"))
        return false;
    return true;
}

typedef void* (*dlsym_t)(void*, const char*);
static dlsym_t g_real_dlsym = nullptr;

static void* wrap_dlsym(void* handle, const char* symbol) {
    void* res = g_real_dlsym(handle, symbol);
    if (!res || !is_arc_process(symbol)) return res;

    void** slot = nullptr;
    void (*tramp)() = nullptr;
    if (!strcmp(symbol, "ARC_Turbo_RAW_Process")) {
        slot = &aps_real_raw;
        tramp = wrap_arc_raw;
    } else if (!strcmp(symbol, "ARC_Turbo_RAW_Bokeh_Process")) {
        slot = &aps_real_raw_bokeh;
        tramp = wrap_arc_raw_bokeh;
    } else if (!strcmp(symbol, "ARC_Turbo_HDR_Process")) {
        slot = &aps_real_hdr;
        tramp = wrap_arc_hdr;
    } else if (!strcmp(symbol, "ARC_Turbo_HDR_Bokeh_Process")) {
        slot = &aps_real_hdr_bokeh;
        tramp = wrap_arc_hdr_bokeh;
    } else {
        // Unknown *Process -- still repair via the HDR trampoline so a renamed
        // sibling does not silently skip the chroma fix.
        slot = &aps_real_hdr;
        tramp = wrap_arc_hdr;
    }
    *slot = res;
    LOGI("interposing %s (real=%p)", symbol, res);
    return (void*)tramp;
}

typedef void (*p010_t)(uint16_t*, uint16_t*, uint32_t, uint32_t, uint32_t, uint32_t);
static p010_t g_real_p010 = nullptr;
static void wrap_p010(uint16_t* dst, uint16_t* src, uint32_t w2, uint32_t w3, uint32_t w4,
                      uint32_t w5) {
    if (w4 > 0) {
        uint64_t sb, ss;
        if (range_of((uint64_t)src, &sb, &ss)) {
            uint64_t avail = (sb + ss) - (uint64_t)src;
            uint32_t new_w5 = (uint32_t)((avail * 2 / 3) / w4);
            if (new_w5 > 0 && new_w5 != w5) {
                LOGI("p010 fix: avail=0x%llx w4=%u w5 %u->%u", (unsigned long long)avail, w4, w5,
                     new_w5);
                w5 = new_w5;
            }
        }
    }
    g_real_p010(dst, src, w2, w3, w4, w5);
}

// ---- ELF JUMP_SLOT discovery (no pinned offsets) ----

static bool elf_ok(const Elf64_Ehdr* eh, uint64_t map_size) {
    if (map_size < sizeof(Elf64_Ehdr)) return false;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return false;
    if (eh->e_ident[EI_CLASS] != ELFCLASS64) return false;
    if (eh->e_machine != EM_AARCH64) return false;
    if (eh->e_phoff == 0 || eh->e_phentsize != sizeof(Elf64_Phdr)) return false;
    return true;
}

// Walk PT_DYNAMIC / JMPREL of the mapped ELF at `base`. Returns file-relative
// GOT offset and (for defined symbols) st_value. `want` is matched exactly
// unless `substr` is true.
static bool elf_find_jmpslot(uint64_t base, uint64_t map_hint, const char* want, bool substr,
                             uint64_t* out_got_off, uint64_t* out_sym_val) {
    uint64_t mb, ms;
    if (!range_of(base, &mb, &ms)) return false;
    // First mapping may be just the ELF header + rodata; dynamic/rela can live
    // in later segments. Use the hint, then fall back to a generous window.
    uint64_t span = ms;
    if (map_hint > span) span = map_hint;

    const Elf64_Ehdr* eh = (const Elf64_Ehdr*)base;
    if (!elf_ok(eh, span)) return false;

    const Elf64_Phdr* ph = (const Elf64_Phdr*)(base + eh->e_phoff);
    const Elf64_Dyn* dyn = nullptr;
    uint64_t load0 = 0;
    bool have_load0 = false;
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_LOAD && !have_load0) {
            load0 = ph[i].p_vaddr;
            have_load0 = true;
        }
        if (ph[i].p_type == PT_DYNAMIC) dyn = (const Elf64_Dyn*)(base + ph[i].p_vaddr - load0);
    }
    if (!dyn) return false;
    uint64_t bias = base - load0;

    const char* strtab = nullptr;
    const Elf64_Sym* symtab = nullptr;
    const Elf64_Rela* jmprel = nullptr;
    uint64_t jmprel_sz = 0;
    const Elf64_Rela* rela = nullptr;
    uint64_t rela_sz = 0;
    for (const Elf64_Dyn* d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_STRTAB:
                strtab = (const char*)(bias + d->d_un.d_ptr);
                break;
            case DT_SYMTAB:
                symtab = (const Elf64_Sym*)(bias + d->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (const Elf64_Rela*)(bias + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                jmprel_sz = d->d_un.d_val;
                break;
            case DT_RELA:
                rela = (const Elf64_Rela*)(bias + d->d_un.d_ptr);
                break;
            case DT_RELASZ:
                rela_sz = d->d_un.d_val;
                break;
            default:
                break;
        }
    }
    if (!strtab || !symtab) return false;

    auto scan = [&](const Elf64_Rela* rel, uint64_t bytes) -> bool {
        if (!rel || !bytes) return false;
        size_t n = bytes / sizeof(Elf64_Rela);
        for (size_t i = 0; i < n; i++) {
            if (ELF64_R_TYPE(rel[i].r_info) != R_AARCH64_JUMP_SLOT) continue;
            uint32_t si = (uint32_t)ELF64_R_SYM(rel[i].r_info);
            const char* nm = strtab + symtab[si].st_name;
            bool hit = substr ? (strstr(nm, want) != nullptr) : (strcmp(nm, want) == 0);
            if (!hit) continue;
            *out_got_off = rel[i].r_offset - load0;
            if (out_sym_val) *out_sym_val = symtab[si].st_value - load0;
            return true;
        }
        return false;
    };

    if (scan(jmprel, jmprel_sz)) return true;
    if (scan(rela, rela_sz)) return true;
    return false;
}

static bool got_slot_mapped(uint64_t slot, void** cur) {
    uint64_t mb, ms;
    if (!range_of(slot, &mb, &ms) || slot + sizeof(void*) > mb + ms) return false;
    if (cur) *cur = *(void**)slot;
    return true;
}

static bool same_module(uint64_t a, uint64_t b) {
    uint64_t ab, as_, bb, bs;
    return range_of(a, &ab, &as_) && range_of(b, &bb, &bs) && ab == bb;
}

static bool g_p010_done = false, g_dlsym_done = false;
static uint64_t g_p010_got = 0, g_p010_func = 0, g_dlsym_got = 0;
static bool g_p010_found = false, g_dlsym_found = false;

static void try_install() {
    uint64_t base;

    if (!g_p010_done && module_base("libAlgoProcess.so", &base)) {
        if (!g_p010_found) {
            uint64_t got = 0, func = 0;
            if (elf_find_jmpslot(base, 0x800000, "p010LSB2MSBNeon", true, &got, &func)) {
                g_p010_got = got;
                g_p010_func = func;
                g_p010_found = true;
                LOGI("ELF p010LSB2MSBNeon GOT=+0x%llx func=+0x%llx", (unsigned long long)got,
                     (unsigned long long)func);
            }
        }
        if (g_p010_found) {
            uint64_t slot = base + g_p010_got;
            void* expect = (void*)(base + g_p010_func);
            void* cur = nullptr;
            if (got_slot_mapped(slot, &cur) && cur == expect) {
                void* old = nullptr;
                if (got_redirect(slot, (void*)wrap_p010, &old)) {
                    g_real_p010 = (p010_t)old;
                    LOGI("GOT-hooked p010 (real=%p)", old);
                    g_p010_done = true;
                }
            }
        }
    }

    if (!g_dlsym_done && module_base("libAlgoInterface.so", &base)) {
        if (!g_dlsym_found) {
            uint64_t got = 0, func = 0;
            if (elf_find_jmpslot(base, 0x2800000, "dlsym", false, &got, &func)) {
                g_dlsym_got = got;
                g_dlsym_found = true;
                LOGI("ELF dlsym GOT=+0x%llx", (unsigned long long)got);
            }
        }
        if (g_dlsym_found) {
            uint64_t slot = base + g_dlsym_got;
            void* cur = nullptr;
            if (got_slot_mapped(slot, &cur) && cur &&
                same_module((uint64_t)cur, (uint64_t)(void*)dlsym)) {
                void* old = nullptr;
                if (got_redirect(slot, (void*)wrap_dlsym, &old)) {
                    g_real_dlsym = (dlsym_t)old;
                    LOGI("GOT-hooked dlsym in libAlgoInterface (real=%p)", old);
                    g_dlsym_done = true;
                }
            }
        }
    }
}

static void* poller(void*) {
    for (int i = 0; i < 24000 && !(g_p010_done && g_dlsym_done); i++) {
        try_install();
        usleep(25 * 1000);
    }
    if (!(g_p010_done && g_dlsym_done))
        LOGW("install incomplete: p010=%d (found=%d) dlsym=%d (found=%d)", g_p010_done,
             g_p010_found, g_dlsym_done, g_dlsym_found);
    return nullptr;
}

__attribute__((constructor)) static void apsfixup_init() {
    LOGI("libapsfixup loaded (pid %d) elf-discover + HDR/RAW Process wrap", getpid());
    try_install();
    if (!(g_p010_done && g_dlsym_done)) {
        pthread_t t;
        pthread_create(&t, nullptr, poller, nullptr);
        pthread_detach(t);
    }
}
