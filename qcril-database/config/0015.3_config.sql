/*
  SPDX-FileCopyrightText: 2026 The LineageOS Project
  SPDX-License-Identifier: Apache-2.0
*/

CREATE TABLE IF NOT EXISTS qcril_properties_table (property TEXT PRIMARY KEY NOT NULL, def_val TEXT, value TEXT);
INSERT OR REPLACE INTO qcril_properties_table(property, def_val) VALUES('qcrildb_version',15.3);
-- Avoid waiting for Qualcomm-only ATEL-ready notifications on AOSP.
-- Replace any cached override when upgrading an existing radio database.
UPDATE qcril_properties_table SET def_val='0', value='0' WHERE property='persist.vendor.radio.poweron_opt';
