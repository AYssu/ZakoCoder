#!/usr/bin/env python3
"""Verify ELF execution/packaging contract and the project's pinned artifact digests."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
NAMES = ("libzakocoder_proot.so", "libzakocoder_loader.so")


def inspect_elf(data, loader=False, abi="arm64-v8a"):
    machines = {"arm64-v8a": 183, "x86_64": 62}
    if abi not in machines:
        raise ValueError(f"Unsupported ABI: {abi}")
    if data[:7] != b"\x7fELF\x02\x01\x01" or len(data) < 64:
        raise ValueError("Expected little-endian ELF64")
    kind, machine = struct.unpack_from("<HH", data, 16)
    entry, phoff = struct.unpack_from("<QQ", data, 24)
    phsize, count = struct.unpack_from("<HH", data, 54)
    if machine != machines[abi] or kind != (2 if loader else 3) or not entry:
        raise ValueError(f"Expected {'ARM64' if abi == 'arm64-v8a' else abi} static loader or PIE PRoot executable")
    if phsize != 56 or not count or phoff + count * phsize > len(data):
        raise ValueError("Invalid ELF program headers")
    headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(count)]
    loads = [h for h in headers if h[0] == 1]
    if not loads or any(h[7] < 16384 or (h[2] - h[3]) % 16384 for h in loads):
        raise ValueError("LOAD segments must support 16 KiB pages")
    if not any(h[1] & 1 and h[3] <= entry < h[3] + h[6] for h in loads):
        raise ValueError("Entry point is outside executable segments")
    if any(h[0] == 0x6474e551 and h[1] & 1 for h in headers):
        raise ValueError("Executable stack is forbidden")
    interpreter = [data[h[2]:h[2] + h[5]].rstrip(b"\0") for h in headers if h[0] == 3]
    dynamic = [h for h in headers if h[0] == 2]
    if loader:
        if interpreter or dynamic:
            raise ValueError("Loader must be freestanding without interpreter/dynamic dependencies")
        return {"kind": f"static {abi} loader", "needed": []}
    if interpreter != [b"/system/bin/linker64"] or len(dynamic) != 1:
        raise ValueError("PRoot must use the Android linker64")
    h = dynamic[0]
    tags = [struct.unpack_from("<qQ", data, i) for i in range(h[2], h[2] + h[5], 16)]
    values = dict(tags)
    if 5 not in values or 10 not in values:
        raise ValueError("Missing dynamic string table")
    strtab = values[5]
    segment = next((h for h in loads if h[3] <= strtab < h[3] + h[5]), None)
    if segment is None:
        raise ValueError("Dynamic strings outside LOAD segment")
    offset = segment[2] + strtab - segment[3]
    strings = data[offset:offset + values[10]]
    needed = [strings[v:].split(b"\0", 1)[0].decode("ascii") for t, v in tags if t == 1]
    if not needed or set(needed) - {"libc.so", "libdl.so", "liblog.so", "libandroid.so"}:
        raise ValueError(f"Unexpected non-system dependency: {needed}")
    if 15 in values or 29 in values:
        raise ValueError("RPATH/RUNPATH must not refer to build/Termux paths")
    if not values.get(0x6ffffffb, 0) & 0x08000000:
        raise ValueError("PRoot must have the PIE flag")
    return {"kind": f"Android {abi} PIE", "needed": needed}


def verify(directory, expected, abi="arm64-v8a"):
    report = {}
    for name in NAMES:
        data = (directory / name).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if expected.get(name) != digest:
            raise ValueError(f"Pinned runtime checksum mismatch: {name}")
        report[name] = dict(inspect_elf(data, loader=name == NAMES[1], abi=abi), sha256=digest)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    args = parser.parse_args()
    lock = json.loads((ROOT / "artifacts.lock.json").read_text())
    expected = (lock if args.abi == "arm64-v8a" else lock["architectures"][args.abi])["artifacts"]
    directory = args.directory or (ROOT / "build" / ("jniLibs/arm64-v8a" if args.abi == "arm64-v8a" else "x86_64/jniLibs/x86_64"))
    print(json.dumps(verify(directory, expected, args.abi), indent=2))


if __name__ == "__main__":
    main()
