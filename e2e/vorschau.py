#!/usr/bin/env python3
"""Write a small preview of an emulator screenshot as text into the GitHub log.

The screenshots in the result artifact cannot be downloaded from a cloud session – this way it can still be seen what
was on the screen. Input: raw image from `adb exec-out screencap` (without -p). Output: PNG (scaled down, few colours)
as Base64 between "PREVIEW <name> BEGIN" and "PREVIEW <name> END".
"""
import base64
import struct
import sys
import zlib

datei, name = sys.argv[1], sys.argv[2]
teil = int(sys.argv[3]) if len(sys.argv) > 3 else 6
roh = open(datei, "rb").read()
if len(roh) < 16:
    print(f"PREVIEW {name}: no image ({len(roh)} bytes)")
    sys.exit(0)
w, h = struct.unpack_from("<II", roh, 0)
kopf = len(roh) - w * h * 4
if w <= 0 or h <= 0 or kopf not in (12, 16):
    print(f"PREVIEW {name}: unknown format ({w}x{h}, header {kopf})")
    sys.exit(0)
bw, bh = w // teil, h // teil
zeilen = bytearray()
for y in range(bh):
    zeilen.append(0)
    start = kopf + (y * teil) * w * 4
    for x in range(bw):
        o = start + x * teil * 4
        # 4 bits per colour are enough to recognise things and keep the image small.
        zeilen += bytes((roh[o] & 0xF0, roh[o + 1] & 0xF0, roh[o + 2] & 0xF0))


def stueck(art, daten):
    return struct.pack(">I", len(daten)) + art + daten + struct.pack(">I", zlib.crc32(art + daten) & 0xFFFFFFFF)


png = b"\x89PNG\r\n\x1a\n" + stueck(b"IHDR", struct.pack(">IIBBBBB", bw, bh, 8, 2, 0, 0, 0)) + \
    stueck(b"IDAT", zlib.compress(bytes(zeilen), 9)) + stueck(b"IEND", b"")
text = base64.b64encode(png).decode()
print(f"PREVIEW {name} BEGIN {w}x{h} -> {bw}x{bh}")
for i in range(0, len(text), 400):
    print(text[i:i + 400])
print(f"PREVIEW {name} END")
