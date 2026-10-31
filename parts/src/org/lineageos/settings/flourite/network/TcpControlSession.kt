/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.network

import android.os.SystemProperties
import java.io.File

/** Keep the initial value and ownership across recreation of the network screen. */
internal object TcpControlSession {
    val manager = TcpControlManager(object : TcpControlPort {
        override fun readCurrent() = File("$SYSCTL/tcp_congestion_control").readText()
        override fun readAvailable() = File("$SYSCTL/tcp_available_congestion_control").readText()
        override fun controlReady() = SystemProperties.get("sys.flouriteparts.tcp_ready") == "1"
        override fun request(algorithm: String) {
            SystemProperties.set("sys.flouriteparts.tcp_congestion", algorithm)
        }
        override fun persistenceReady() = SystemProperties.get("sys.flouriteparts.tcp_persist_ready") == "1"
        override fun readSaved() = SystemProperties.get("persist.sys.flouriteparts.tcp_congestion", "off")
        override fun save(algorithm: String) {
            SystemProperties.set("persist.sys.flouriteparts.tcp_congestion", algorithm)
        }
    })

    private const val SYSCTL = "/proc/sys/net/ipv4"
}
