#!/usr/bin/env python3
"""Extract a flourite first-stage V3 record from a raw oops partition dump."""

import argparse
import re
import struct
from pathlib import Path


RECORD_SIZE = 2 * 1024 * 1024
HEADER_SIZE = 4096
MARKER = b"FLOURITE_FIRST_STAGE_DIAG_V3"
MTDOOPS_MAGICS = {0x5D005D00, 0x5D005E00}


def parse_header(record: bytes) -> tuple[int, dict[str, str]] | None:
    sequence, magic = struct.unpack_from("<II", record)
    if magic not in MTDOOPS_MAGICS:
        return None

    header = record[16:HEADER_SIZE].split(b"\0", 1)[0]
    if MARKER not in header:
        return None

    values: dict[str, str] = {}
    for line in header.decode("utf-8", "replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return sequence, values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path, help="raw dump of the oops partition")
    parser.add_argument("output", type=Path, help="destination text log")
    args = parser.parse_args()

    contents = args.dump.read_bytes()
    if len(contents) < RECORD_SIZE or len(contents) % RECORD_SIZE != 0:
        parser.error("oops dump size is not a non-zero multiple of 2 MiB")

    matches: list[tuple[int, int, dict[str, str]]] = []
    for index in range(len(contents) // RECORD_SIZE):
        base = index * RECORD_SIZE
        parsed = parse_header(contents[base : base + RECORD_SIZE])
        if parsed is not None:
            sequence, values = parsed
            matches.append((sequence, index, values))

    if not matches:
        parser.error("no FLOURITE_FIRST_STAGE_DIAG_V3 record found")

    sequence, index, values = max(matches, key=lambda match: match[0])
    log_bytes_text = values.get("log_bytes", "")
    if not re.fullmatch(r"\d+", log_bytes_text):
        parser.error("V3 record has an invalid log_bytes field")

    log_bytes = int(log_bytes_text)
    maximum = RECORD_SIZE - HEADER_SIZE
    if log_bytes > maximum:
        parser.error(f"V3 record requests {log_bytes} bytes; maximum is {maximum}")

    base = index * RECORD_SIZE
    payload = contents[base + HEADER_SIZE : base + HEADER_SIZE + log_bytes]
    summary = (
        f"{MARKER.decode()}\n"
        f"record_index={index}\n"
        f"sequence={sequence}\n"
        + "".join(
            f"{key}={value}\n"
            for key, value in values.items()
            if key not in {"record_index", "sequence"}
        )
        + "\n"
    ).encode()
    args.output.write_bytes(summary + payload)

    print(
        f"extracted record {index}, sequence {sequence}, {log_bytes} log bytes "
        f"to {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
