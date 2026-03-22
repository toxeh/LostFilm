#!/usr/bin/env python3
"""
TLS ClientHello split test.
Sends a real TLS ClientHello to www.lostfilm.tv, split into N TLS records.
Tells us if Cloudflare accepts fragmented handshakes at all.

Usage:
  python3 tls_split_test.py [splits]
  python3 tls_split_test.py 0   # no split (baseline)
  python3 tls_split_test.py 2   # split into 2 records at SNI middle
  python3 tls_split_test.py 6   # split into 6 records
"""

import socket
import ssl
import struct
import sys
import time

HOST = "www.lostfilm.tv"
PORT = 443

def make_client_hello(host: str) -> bytes:
    """Build a minimal TLS 1.3 ClientHello for host."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    # Capture ClientHello via a pair of connected sockets
    server_sock, client_sock = socket.socketpair()
    client_hello = b""

    try:
        # Wrap the client side; startHandshake will write ClientHello
        ssl_sock = ctx.wrap_socket(client_sock, server_side=False,
                                   server_hostname=host,
                                   do_handshake_on_connect=False)
        ssl_sock.setblocking(False)
        try:
            ssl_sock.do_handshake()
        except ssl.SSLWantReadError:
            pass
        except ssl.SSLWantWriteError:
            pass
        # Read what landed in the server-side buffer
        server_sock.setblocking(False)
        try:
            client_hello = server_sock.recv(65535)
        except BlockingIOError:
            pass
    finally:
        server_sock.close()
        client_sock.close()

    return client_hello


def find_sni(payload: bytes, host: str) -> int:
    """Return index of hostname bytes inside TLS record payload."""
    needle = host.encode("ascii")
    idx = payload.find(needle)
    return idx


def split_record(payload: bytes, splits: list[int], ct: int, ver: tuple) -> list[bytes]:
    """
    Split a TLS record payload into multiple records at given byte offsets.
    Each chunk gets its own 5-byte TLS header.
    """
    records = []
    prev = 0
    for sp in sorted(splits) + [len(payload)]:
        chunk = payload[prev:sp]
        if not chunk:
            continue
        hdr = bytes([ct, ver[0], ver[1],
                     (len(chunk) >> 8) & 0xFF,
                      len(chunk)       & 0xFF])
        records.append(hdr + chunk)
        prev = sp
    return records


def test(n_splits: int):
    print(f"\n{'='*60}")
    print(f"Test: split into {n_splits if n_splits > 0 else 1} TLS record(s)")
    print(f"{'='*60}")

    raw_hello = make_client_hello(HOST)
    if not raw_hello or raw_hello[0] != 0x16:
        print("ERROR: couldn't capture ClientHello")
        return

    ct    = raw_hello[0]
    ver   = (raw_hello[1], raw_hello[2])
    rlen  = (raw_hello[3] << 8) | raw_hello[4]
    payload = raw_hello[5:5+rlen]

    sni_idx = find_sni(payload, HOST)
    print(f"ClientHello: {rlen} bytes, SNI '{HOST}' at byte {sni_idx}")

    # Decide split positions
    if n_splits <= 1 or sni_idx < 0:
        records = [raw_hello]
    elif n_splits == 2:
        mid = sni_idx + len(HOST) // 2
        records = split_record(payload, [mid], ct, ver)
    else:
        sni_end = sni_idx + len(HOST)
        sni_mid = (sni_idx + sni_end) // 2
        pre     = max(1, sni_idx // 2)
        post    = min(rlen - 1, sni_end + 4)
        pts = sorted(set([pre, sni_idx + 1, sni_mid, sni_end - 1, post]))
        pts = [p for p in pts if 0 < p < rlen]
        records = split_record(payload, pts[:n_splits-1], ct, ver)

    print(f"Sending {len(records)} TLS record(s):")
    for i, r in enumerate(records):
        print(f"  rec[{i}]: {len(r)} bytes")

    # Connect raw TCP to Cloudflare IP
    ip = socket.gethostbyname(HOST)
    print(f"\nConnecting to {ip}:443 ...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    try:
        sock.connect((ip, PORT))
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print("Connected!")

        t0 = time.time()
        for i, rec in enumerate(records):
            sock.sendall(rec)
            print(f"  Sent rec[{i}] ({len(rec)} bytes)")
            if i < len(records) - 1:
                time.sleep(0.2)  # 200ms pause between records

        print(f"\nWaiting for ServerHello (10s timeout)...")
        sock.settimeout(10)
        try:
            response = sock.recv(4096)
            elapsed = time.time() - t0
            if response and response[0] == 0x16:
                print(f"✅ GOT ServerHello! {len(response)} bytes in {elapsed:.2f}s")
                # Parse record type
                rtype = response[5] if len(response) > 5 else 0
                print(f"   Handshake type: {rtype} ({'ServerHello' if rtype == 2 else 'other'})")
            elif response and response[0] == 0x15:
                print(f"❌ Got TLS Alert! ({len(response)} bytes) - server rejected handshake")
                if len(response) >= 7:
                    print(f"   Alert level={response[5]}, code={response[6]}")
            else:
                print(f"⚠️  Got unexpected data: {response[:20].hex()}")
        except socket.timeout:
            elapsed = time.time() - t0
            print(f"❌ TIMEOUT after {elapsed:.1f}s — server did not respond")
    except Exception as e:
        print(f"❌ Error: {e}")
    finally:
        sock.close()


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    # Always run baseline first
    if n != 0:
        test(0)   # no split (baseline)
        time.sleep(1)
    test(n)       # with split
