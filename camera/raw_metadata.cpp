// SPDX-License-Identifier: Apache-2.0
#define LOG_TAG "FlouriteCameraMetadata"
#include "raw_metadata.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <log/log.h>
#include <system/camera_metadata.h>

// Stock MiMetadata is opaque. Call its exported methods with the AArch64
// member-function ABI; never instantiate it or depend on its object layout.
extern "C" int miUpdateI32(void*, uint32_t, const int32_t*, size_t)
        asm("_ZN10MiMetadata6updateEjPKim");
extern "C" const camera_metadata_t* miAndroidMetadata(const void*)
        asm("_ZNK10MiMetadata13toAndroidMetaEv");
extern "C" int miGetTag(const char*, uint32_t*)
        asm("_ZN10MiMetadata14getTagFromNameEPKcPj");

namespace flourite::camera {
namespace {
constexpr int32_t kRaw16 = 32;
constexpr int32_t kOutput = 0;
constexpr char kVendorStreams[] = "xiaomi.scaler.availableStreamConfigurations";
static_assert(ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS == 0xd000a);

bool validStreams(const int32_t* data, size_t count) {
    if (!data || !count || count % 4 || count > kMaxStreamInts) return false;
    for (size_t i = 0; i < count; i += 4) {
        if (data[i + 1] <= 0 || data[i + 2] <= 0 ||
            (data[i + 3] != 0 && data[i + 3] != 1)) return false;
    }
    return true;
}

// Audited OS3.0.304.0/OS3.0.306.0 libmicamera_hal_core.so only. These two
// return PCs and their stream-tag semantics are unchanged in 306.
// Extraction pins the entire .text
// (including these BL instructions), ELF architecture and original import.
// These are return PCs, not function entry points or file offsets. The wrapper
// is imported by this one DSO only. All other update(int32) calls pass through.
UpdateSite updateSite(void* caller) {
    static const uintptr_t base = [caller]() -> uintptr_t {
        Dl_info info{};
        if (!dladdr(caller, &info) || !info.dli_fname) return 0;
        const char* name = std::strrchr(info.dli_fname, '/');
        name = name ? name + 1 : info.dli_fname;
        if (std::strcmp(name, "libmicamera_hal_core.so")) return 0;
        return reinterpret_cast<uintptr_t>(info.dli_fbase);
    }();
    if (!base) return UpdateSite::Other;
    const uintptr_t offset = reinterpret_cast<uintptr_t>(caller) - base;
    if (offset == 0x24847c) return UpdateSite::VendorStreams;
    if (offset == 0x248448) return UpdateSite::LegacyDngStreams;
    return UpdateSite::Other;
}
}  // namespace

bool mergeRaw16(const int32_t* standard, size_t standardCount,
                const int32_t* extended, size_t extendedCount,
                std::vector<int32_t>& result) {
    result.clear();
    if (!validStreams(standard, standardCount) ||
        !validStreams(extended, extendedCount)) return false;
    result.assign(standard, standard + standardCount);
    for (size_t i = 0; i < extendedCount; i += 4) {
        if (extended[i] != kRaw16 || extended[i + 3] != kOutput) continue;
        bool exists = false;
        for (size_t j = 0; j < result.size(); j += 4) {
            if (std::equal(extended + i, extended + i + 4, result.data() + j)) {
                exists = true;
                break;
            }
        }
        if (exists) continue;
        if (result.size() > kMaxStreamInts - 4) {
            result.clear();
            return false;
        }
        result.insert(result.end(), extended + i, extended + i + 4);
    }
    return true;
}

int updateStaticStreams(void* metadata, uint32_t tag, const int32_t* data,
                        size_t count, UpdateSite site) {
    if (site == UpdateSite::Other) return miUpdateI32(metadata, tag, data, count);

    uint32_t vendorTag = 0;
    const bool legacy = site == UpdateSite::LegacyDngStreams;
    if (miGetTag(kVendorStreams, &vendorTag) || vendorTag < 0x80000000u ||
        (legacy ? tag != ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS : tag != vendorTag)) {
        ALOGE("Unexpected mock-camera stream tag; keeping standard JPEG metadata");
        // Never let a persisted R6 setDngTag=true overwrite the safe standard
        // list. On the ordinary vendor branch, preserve the stock operation.
        return legacy ? -EINVAL : miUpdateI32(metadata, tag, data, count);
    }

    const int status = miUpdateI32(metadata, vendorTag, data, count);
    if (status) return status;
    // toAndroidMeta() returns a borrowed snapshot owned by MiMetadata. Copy
    // the entry before the next update; do not release or modify this pointer.
    const camera_metadata_t* androidMeta = miAndroidMetadata(metadata);
    camera_metadata_ro_entry_t entry{};
    if (!androidMeta || find_camera_metadata_ro_entry(androidMeta,
            ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry) || entry.type != TYPE_INT32) {
        ALOGW("No standard stream metadata; RAW extension skipped");
        return status;
    }
    std::vector<int32_t> merged;
    if (!mergeRaw16(entry.data.i32, entry.count, data, count, merged)) {
        ALOGW("Invalid stream metadata; RAW extension skipped");
        return status;
    }
    const size_t added = (merged.size() - entry.count) / 4;
    if (!added) return status;
    const int rawStatus = miUpdateI32(metadata, ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                     merged.data(), merged.size());
    if (rawStatus) ALOGE("RAW metadata update failed: %d", rawStatus);
    else ALOGI("Added %zu RAW16 output configurations; original JPEG table preserved", added);
    return status;
}
}  // namespace flourite::camera

extern "C" __attribute__((visibility("default"), noinline))
int flourite_metadata_update_i32(void* metadata, uint32_t tag,
                                        const int32_t* data, size_t count) {
    void* caller = __builtin_extract_return_addr(__builtin_return_address(0));
    return flourite::camera::updateStaticStreams(metadata, tag, data, count,
                                                flourite::camera::updateSite(caller));
}
