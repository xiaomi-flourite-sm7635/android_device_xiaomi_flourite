/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "PowerMode.flourite"

#include <aidl/android/hardware/power/BnPower.h>
#include <android-base/logging.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_stability.h>
#include <android/binder_status.h>

#include <mutex>

using aidl::android::hardware::power::Mode;

namespace {

constexpr int32_t kPrimaryTouchId = 0;
constexpr int32_t kTouchModeDoubleTap = 14;
constexpr int32_t kSetModeValueTransaction = FIRST_CALL_TRANSACTION + 8;
constexpr char kTouchFeatureService[] =
        "vendor.xiaomi.hw.touchfeature.ITouchFeature/default";
constexpr char kTouchFeatureDescriptor[] = "vendor.xiaomi.hw.touchfeature.ITouchFeature";

std::mutex gTouchLock;
ndk::SpAIBinder gTouchFeatureBinder;

binder_status_t touchFeatureOnTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}

AIBinder_Class* getTouchFeatureClass() {
    static AIBinder_Class* clazz = AIBinder_Class_define(
            kTouchFeatureDescriptor, [](void*) -> void* { return nullptr; }, [](void*) {},
            touchFeatureOnTransact);
    return clazz;
}

bool ensureTouchFeatureBinderLocked() {
    if (gTouchFeatureBinder.get() != nullptr) {
        return true;
    }

    AIBinder_Class* clazz = getTouchFeatureClass();
    if (clazz == nullptr) {
        LOG(ERROR) << "failed to create TouchFeature binder class";
        return false;
    }

    AIBinder* binder = AServiceManager_waitForService(kTouchFeatureService);
    if (binder == nullptr) {
        LOG(ERROR) << "TouchFeature service is unavailable";
        return false;
    }
    if (!AIBinder_associateClass(binder, clazz)) {
        LOG(ERROR) << "failed to associate TouchFeature binder class";
        AIBinder_decStrong(binder);
        return false;
    }

    gTouchFeatureBinder = ndk::SpAIBinder(binder);
    return true;
}

bool setDoubleTapMode(bool enabled) {
    std::lock_guard<std::mutex> lock(gTouchLock);
    if (!ensureTouchFeatureBinderLocked()) {
        return false;
    }

    AParcel* in = nullptr;
    binder_status_t status = AIBinder_prepareTransaction(gTouchFeatureBinder.get(), &in);
    if (status != STATUS_OK) {
        LOG(ERROR) << "failed to prepare TouchFeature transaction: " << status;
        gTouchFeatureBinder = nullptr;
        return false;
    }

    status = AParcel_writeInt32(in, kPrimaryTouchId);
    status = status == STATUS_OK ? AParcel_writeInt32(in, kTouchModeDoubleTap) : status;
    status = status == STATUS_OK ? AParcel_writeInt32(in, enabled ? 1 : 0) : status;
    if (status != STATUS_OK) {
        LOG(ERROR) << "failed to write TouchFeature transaction: " << status;
        AParcel_delete(in);
        return false;
    }

    AParcel* out = nullptr;
    status = AIBinder_transact(gTouchFeatureBinder.get(), kSetModeValueTransaction, &in, &out,
                               FLAG_PRIVATE_LOCAL);
    if (status != STATUS_OK) {
        LOG(ERROR) << "TouchFeature setModeValue failed: " << status;
        gTouchFeatureBinder = nullptr;
        return false;
    }

    AStatus* aidlStatus = nullptr;
    status = AParcel_readStatusHeader(out, &aidlStatus);
    bool ok = status == STATUS_OK && aidlStatus != nullptr && AStatus_isOk(aidlStatus);
    if (ok) {
        int32_t result = 0;
        status = AParcel_readInt32(out, &result);
        ok = status == STATUS_OK && result == 0;
        if (!ok) {
            LOG(ERROR) << "TouchFeature setModeValue returned " << result
                       << " with parcel status " << status;
        }
    } else {
        LOG(ERROR) << "TouchFeature setModeValue returned binder status "
                   << (aidlStatus != nullptr ? AStatus_getStatus(aidlStatus) : status);
    }

    if (aidlStatus != nullptr) {
        AStatus_delete(aidlStatus);
    }
    AParcel_delete(out);

    if (!ok) {
        return false;
    }
    LOG(INFO) << "double-tap touch mode set to " << enabled;
    return true;
}

}  // namespace

namespace aidl::android::hardware::power::impl {

bool isDeviceSpecificModeSupported(Mode type, bool* out) {
    if (type != Mode::DOUBLE_TAP_TO_WAKE) {
        return false;
    }

    *out = true;
    return true;
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
    if (type != Mode::DOUBLE_TAP_TO_WAKE) {
        return false;
    }

    setDoubleTapMode(enabled);
    return true;
}

}  // namespace aidl::android::hardware::power::impl
