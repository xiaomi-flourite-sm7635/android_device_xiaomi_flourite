/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <stdint.h>
#include <sys/ioctl.h>

namespace xiaomi::touch {

constexpr int8_t kPrimaryTouchId = 0;
constexpr uint8_t kSetCurrentValue = 0;
constexpr uint16_t kNonUiMode = 17;

struct CommonData {
    int8_t touchId;
    uint8_t command;
    uint16_t mode;
    uint16_t dataLength;
    int32_t data[256];
};

static_assert(sizeof(CommonData) == 1032);

constexpr unsigned int kSelectTouchIdIoctl = _IO('T', 3);
constexpr unsigned int kCommonDataIoctl = _IOW('T', 0, CommonData);

}  // namespace xiaomi::touch
