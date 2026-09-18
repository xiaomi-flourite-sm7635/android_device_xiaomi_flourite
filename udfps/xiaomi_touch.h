/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <sys/ioctl.h>

/*
 * Userspace ABI exported by Flourite's stock xiaomi_touch.ko. Keep this
 * local: the source-built Peridot kernel exposes a newer, incompatible
 * touch_mode_request ABI while Flourite currently ships the stock module.
 *
 * The values below were verified against the BTF embedded in the Flourite
 * HyperOS 3 xiaomi_touch.ko rather than copied from another device.
 */
#define XIAOMI_TOUCH_MAX_BUF_SIZE 256
#define XIAOMI_TOUCH_MAGIC 'T'

enum xiaomi_touch_command {
    XIAOMI_TOUCH_SET_CUR_VALUE = 0,
};

enum xiaomi_touch_mode {
    XIAOMI_TOUCH_FOD_ENABLE = 10,
    XIAOMI_TOUCH_FOD_ICON_ENABLE = 16,
    XIAOMI_TOUCH_FOD_DOWNUP_CTL = 1001,
};

#define XIAOMI_TOUCH_IOC_SET_CUR_VALUE \
    _IO(XIAOMI_TOUCH_MAGIC, XIAOMI_TOUCH_SET_CUR_VALUE)
