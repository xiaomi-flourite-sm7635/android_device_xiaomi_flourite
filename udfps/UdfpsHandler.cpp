/*
 * Copyright (C) 2022-2025 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "UdfpsHandler.flourite"

#include <aidl/android/hardware/biometrics/fingerprint/BnFingerprint.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>

#include <bitset>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <fstream>
#include <thread>

#include <display/drm/mi_disp.h>

#include "UdfpsHandler.h"
#include "xiaomi_touch.h"

#define COMMAND_NIT 10
#define TARGET_BRIGHTNESS_OFF 0
#define TARGET_BRIGHTNESS_1000NIT 1
#define TARGET_BRIGHTNESS_110NIT 6

#define LOW_BRIGHTNESS_THRESHHOLD 100

#define COMMAND_FOD_PRESS_STATUS 1
#define COMMAND_FOD_PRESS_X 2
#define COMMAND_FOD_PRESS_Y 3
#define PARAM_FOD_PRESSED 1
#define PARAM_FOD_RELEASED 0

#define DISP_FEATURE_PATH "/dev/mi_display/disp_feature"
#define TOUCH_DEV_PATH "/dev/xiaomi-touch"

using ::aidl::android::hardware::biometrics::fingerprint::AcquiredInfo;

namespace {

// These event bits live in the Flourite display driver's internal
// mi_disp_lhbm.h and are intentionally absent from its exported UAPI header.
constexpr __u32 kFodLowBrightnessCapture = 1U << 2;
constexpr __u32 kLocalHbmUiReady = 1U << 3;

static std::shared_ptr<disp_event_resp> parseDispEvent(int fd) {
    // mi_disp_read() only returns complete events. FOD events consist of the
    // event header plus a u32 payload, so consume both in one read.
    constexpr size_t kEventSize = sizeof(disp_event) + sizeof(__u32);
    std::shared_ptr<disp_event_resp> response(
            static_cast<disp_event_resp*>(malloc(kEventSize)), free);
    if (!response) {
        LOG(ERROR) << "failed to allocate memory for display event response";
        return nullptr;
    }

    ssize_t eventSize = read(fd, response.get(), kEventSize);
    if (eventSize < 0) {
        PLOG(ERROR) << "failed to read display event";
        return nullptr;
    }

    if (eventSize != static_cast<ssize_t>(kEventSize) ||
        response->base.length != static_cast<__u32>(eventSize)) {
        LOG(ERROR) << "unexpected display event size: read=" << eventSize
                   << ", reported=" << response->base.length;
        return nullptr;
    }

    return response;
}

struct disp_base displayBasePrimary = {
        .flag = 0,
        .disp_id = MI_DISP_PRIMARY,
};

static bool runIoctl(int fd, unsigned long request, void* data, const char* operation) {
    if (fd < 0) {
        LOG(ERROR) << operation << ": device is not open";
        return false;
    }

    if (ioctl(fd, request, data) < 0) {
        PLOG(ERROR) << operation << " failed";
        return false;
    }

    return true;
}

static bool setTouchFingerState(int fd, bool pressed) {
    int request[XIAOMI_TOUCH_MAX_BUF_SIZE] = {
            MI_DISP_PRIMARY,
            XIAOMI_TOUCH_FOD_DOWNUP_CTL,
            pressed ? 1 : 0,
    };

    return runIoctl(fd, XIAOMI_TOUCH_IOC_SET_CUR_VALUE, request,
                    pressed ? "set FOD finger-down touch state"
                            : "set FOD finger-up touch state");
}

}  // anonymous namespace

class FlouriteUdfpsHandler : public UdfpsHandler {
  public:
    void init(fingerprint_device_t* device) {
        mDevice = device;
        disp_fd_ = android::base::unique_fd(open(DISP_FEATURE_PATH, O_RDWR));
        touch_fd_ = android::base::unique_fd(open(TOUCH_DEV_PATH, O_RDWR));
        if (disp_fd_ < 0) {
            PLOG(ERROR) << "failed to open " << DISP_FEATURE_PATH;
        }
        if (touch_fd_ < 0) {
            PLOG(ERROR) << "failed to open " << TOUCH_DEV_PATH;
        }

        // Thread to listen for fod ui changes
        std::thread([this]() {
            android::base::unique_fd fd(open(DISP_FEATURE_PATH, O_RDWR));
            if (fd < 0) {
                LOG(ERROR) << "failed to open " << DISP_FEATURE_PATH << " , err: " << fd;
                return;
            }

            // Register for FOD events
            struct disp_event_req displayEventRequest = {
                    .base = displayBasePrimary,
                    .type = MI_DISP_EVENT_FOD,
            };
            if (ioctl(fd.get(), MI_DISP_IOCTL_REGISTER_EVENT, &displayEventRequest) < 0) {
                LOG(ERROR) << "failed to register FOD event";
                return;
            }

            struct pollfd dispEventPoll = {
                    .fd = fd.get(),
                    .events = POLLIN,
                    .revents = 0,
            };

            while (true) {
                int rc = poll(&dispEventPoll, 1, -1);
                if (rc < 0) {
                    PLOG(ERROR) << "failed to poll " << DISP_FEATURE_PATH;
                    continue;
                }

                if (dispEventPoll.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    LOG(ERROR) << "display event fd failed, revents="
                               << dispEventPoll.revents;
                    return;
                }

                if (!(dispEventPoll.revents & POLLIN)) {
                    continue;
                }

                std::shared_ptr<disp_event_resp> response = parseDispEvent(fd.get());
                if (!response) {
                    continue;
                }

                if (response->base.type != MI_DISP_EVENT_FOD) {
                    LOG(ERROR) << "unexpected display event: " << response->base.type;
                    continue;
                }

                int value = response->data[0];
                LOG(DEBUG) << "received data: " << std::bitset<8>(value);

                bool localHbmUiReady = value & kLocalHbmUiReady;
                bool requestLowBrightnessCapture = value & kFodLowBrightnessCapture;

                mDevice->extCmd(mDevice, COMMAND_NIT,
                                localHbmUiReady
                                        ? (requestLowBrightnessCapture ? TARGET_BRIGHTNESS_110NIT
                                                                       : TARGET_BRIGHTNESS_1000NIT)
                                        : TARGET_BRIGHTNESS_OFF);
            }
        }).detach();
    }

    void onFingerDown(uint32_t x, uint32_t y, float /*minor*/, float /*major*/) {
        if (mAuthSuccess) return;
        LOG(DEBUG) << __func__ << "x: " << x << ", y: " << y;

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_X, x);
        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_Y, y);
        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_PRESSED);

        setTouchFingerState(touch_fd_.get(), true);

        // Request HBM
        struct disp_local_hbm_req displayLhbmRequest = {
                .base = displayBasePrimary,
                .local_hbm_value = LHBM_TARGET_BRIGHTNESS_WHITE_1000NIT,
        };
        runIoctl(disp_fd_.get(), MI_DISP_IOCTL_SET_LOCAL_HBM, &displayLhbmRequest,
                 "enable local HBM");
    }

    void onFingerUp() {
        LOG(DEBUG) << __func__;

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_X, 0);
        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_Y, 0);
        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_RELEASED);

        // Disable HBM
        struct disp_local_hbm_req displayLhbmRequest = {
                .base = displayBasePrimary,
                .local_hbm_value = LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP,
        };
        runIoctl(disp_fd_.get(), MI_DISP_IOCTL_SET_LOCAL_HBM, &displayLhbmRequest,
                 "disable local HBM");

        setTouchFingerState(touch_fd_.get(), false);
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(DEBUG) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        switch (static_cast<AcquiredInfo>(result)) {
            case AcquiredInfo::GOOD:
            case AcquiredInfo::PARTIAL:
            case AcquiredInfo::INSUFFICIENT:
            case AcquiredInfo::SENSOR_DIRTY:
            case AcquiredInfo::TOO_SLOW:
            case AcquiredInfo::TOO_FAST:
            case AcquiredInfo::TOO_DARK:
            case AcquiredInfo::TOO_BRIGHT:
            case AcquiredInfo::IMMOBILE:
            case AcquiredInfo::LIFT_TOO_SOON:
                onFingerUp();
                break;
            default:
                break;
        }
    }

    void onAuthenticationSucceeded() {
        mAuthSuccess = true;
        onFingerUp();
        std::thread([this]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            mAuthSuccess = false;
        }).detach();
    }

    void onAuthenticationFailed() { onFingerUp(); }

  private:
    fingerprint_device_t* mDevice;
    android::base::unique_fd disp_fd_;
    android::base::unique_fd touch_fd_;
    bool mAuthSuccess = false;
};

static UdfpsHandler* create() {
    return new FlouriteUdfpsHandler();
}

static void destroy(UdfpsHandler* handler) {
    delete handler;
}

extern "C" UdfpsHandlerFactory UDFPS_HANDLER_FACTORY = {
        .create = create,
        .destroy = destroy,
};
