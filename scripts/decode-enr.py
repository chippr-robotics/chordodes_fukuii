#!/usr/bin/env python3
"""decode-enr.py — Decode an EIP-778 ENR record to an enode:// URL.

Usage:
  echo "enr:<base64url>" | python3 decode-enr.py
  python3 decode-enr.py "enr:<base64url>"

Prints enode://<128-hex-node-id>@<ip>:<port> or nothing on failure.
No external dependencies — stdlib only.
"""

import sys
import base64
import socket
import struct

# secp256k1 field prime
_P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F


def _decompress_secp256k1(compressed: bytes) -> bytes:
    if len(compressed) != 33:
        raise ValueError(f"expected 33 bytes, got {len(compressed)}")
    prefix = compressed[0]
    x = int.from_bytes(compressed[1:], "big")
    y_sq = (pow(x, 3, _P) + 7) % _P
    y = pow(y_sq, (_P + 1) // 4, _P)
    if (y & 1) != (prefix & 1):
        y = _P - y
    return x.to_bytes(32, "big") + y.to_bytes(32, "big")


def _rlp_decode_items(data: bytes) -> list:
    """Decode the items of an RLP-encoded list. Returns list of bytes objects."""
    if not data:
        raise ValueError("empty data")
    b = data[0]
    if b < 0xC0:
        raise ValueError(f"expected RLP list, got prefix 0x{b:02x}")
    if b <= 0xF7:
        payload = data[1 : 1 + (b - 0xC0)]
    else:
        lol = b - 0xF7
        length = int.from_bytes(data[1 : 1 + lol], "big")
        payload = data[1 + lol : 1 + lol + length]

    items = []
    pos = 0
    while pos < len(payload):
        b = payload[pos]
        if b < 0x80:
            # single byte value
            items.append(bytes([b]))
            pos += 1
        elif b <= 0xB7:
            # short string
            n = b - 0x80
            items.append(payload[pos + 1 : pos + 1 + n])
            pos += 1 + n
        elif b <= 0xBF:
            # long string
            lol = b - 0xB7
            n = int.from_bytes(payload[pos + 1 : pos + 1 + lol], "big")
            items.append(payload[pos + 1 + lol : pos + 1 + lol + n])
            pos += 1 + lol + n
        elif b <= 0xF7:
            # short list — treat as opaque bytes (e.g. eth fork-id)
            n = b - 0xC0
            items.append(payload[pos : pos + 1 + n])
            pos += 1 + n
        else:
            # long list
            lol = b - 0xF7
            n = int.from_bytes(payload[pos + 1 : pos + 1 + lol], "big")
            items.append(payload[pos : pos + 1 + lol + n])
            pos += 1 + lol + n

    return items


def decode_enr(enr_str: str):
    enr_str = enr_str.strip()
    if enr_str.startswith("enr:"):
        enr_str = enr_str[4:]

    # base64url decode — add padding if needed
    pad = 4 - len(enr_str) % 4
    if pad != 4:
        enr_str += "=" * pad
    try:
        data = base64.urlsafe_b64decode(enr_str)
    except Exception:
        return None

    try:
        items = _rlp_decode_items(data)
    except Exception:
        return None

    # ENR layout: [signature, seq, k1, v1, k2, v2, ...]
    # Skip items[0] (signature) and items[1] (seq)
    if len(items) < 4:
        return None

    kv = {}
    i = 2
    while i + 1 < len(items):
        try:
            key = items[i].decode("ascii")
        except Exception:
            i += 2
            continue
        kv[key] = items[i + 1]
        i += 2

    if "ip" not in kv or "tcp" not in kv or "secp256k1" not in kv:
        return None

    try:
        ip = socket.inet_ntoa(kv["ip"])
        tcp = struct.unpack(">H", kv["tcp"])[0]
        if tcp == 0:
            return None
        node_id = _decompress_secp256k1(kv["secp256k1"]).hex()
    except Exception:
        return None

    return f"enode://{node_id}@{ip}:{tcp}"


def main():
    if len(sys.argv) > 1:
        enr = sys.argv[1]
    else:
        enr = sys.stdin.read()

    result = decode_enr(enr.strip())
    if result:
        print(result)


if __name__ == "__main__":
    main()
