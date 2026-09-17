/*
  SPDX-FileCopyrightText: 2026 The LineageOS Project
  SPDX-License-Identifier: Apache-2.0
*/

CREATE TABLE IF NOT EXISTS qcril_properties_table (property TEXT PRIMARY KEY NOT NULL, def_val TEXT, value TEXT);
INSERT OR REPLACE INTO qcril_properties_table(property, def_val) VALUES('qcrildb_version',15.2);
-- Keep the stock flourite ATEL-ready handshake enabled. QtiTelephony uses this
-- path to notify the vendor RIL after SIM records become available.
UPDATE qcril_properties_table SET def_val="1" WHERE property="persist.vendor.radio.poweron_opt";
