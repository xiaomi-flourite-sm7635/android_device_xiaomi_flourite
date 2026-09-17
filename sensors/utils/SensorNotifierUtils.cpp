/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "SensorNotifierUtils"

#include "SensorNotifierUtils.h"

#include <android-base/logging.h>

bool readBool(int fd) {
    char c;
    int rc;

    rc = lseek(fd, 0, SEEK_SET);
    if (rc) {
        LOG(ERROR) << "failed to seek fd, err: " << rc;
        return false;
    }

    rc = read(fd, &c, sizeof(char));
    if (rc != 1) {
        LOG(ERROR) << "failed to read bool from fd, err: " << rc;
        return false;
    }

    return c != '0';
}

disp_event_resp* parseDispEvent(int fd) {
    // mi_disp_read() only returns complete events. Reading just the header leaves
    // the event queued forever because POWER and FOD events also carry a u32.
    constexpr size_t kEventSize = sizeof(disp_event) + sizeof(__u32);
    auto* response = reinterpret_cast<disp_event_resp*>(malloc(kEventSize));
    if (response == nullptr) {
        LOG(ERROR) << "failed to allocate display event response";
        return nullptr;
    }

    ssize_t eventSize = read(fd, response, kEventSize);
    if (eventSize < 0) {
        PLOG(ERROR) << "failed to read display event";
        free(response);
        return nullptr;
    }

    if (eventSize != static_cast<ssize_t>(kEventSize) ||
        response->base.length != static_cast<__u32>(eventSize)) {
        LOG(ERROR) << "unexpected display event size: read=" << eventSize
                   << ", reported=" << response->base.length;
        free(response);
        return nullptr;
    }

    return response;
}
