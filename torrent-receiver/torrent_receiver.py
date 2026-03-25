#!/usr/bin/env python3
"""
UDP Torrent Receiver — слушает broadcast от LostFilm TV и кладёт .torrent в watch-dir Transmission.

Использование:
  python3 torrent_receiver.py [--port 41000] [--watch-dir /path/to/watch]

По умолчанию:
  - Порт: 41000
  - Watch-dir: ~/Downloads/torrents/ (создаст если нет)

Transmission: в settings.json включить watch-dir:
  "watch-dir": "/path/to/watch",
  "watch-dir-enabled": true
"""

import argparse
import os
import socket
import sys
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="UDP Torrent Receiver for LostFilm TV")
    parser.add_argument("--port", type=int, default=41000, help="UDP port to listen on (default: 41000)")
    parser.add_argument("--watch-dir", type=str, default=os.path.expanduser("~/Downloads/torrents"),
                        help="Directory to save .torrent files")
    args = parser.parse_args()

    watch_dir = Path(args.watch_dir)
    watch_dir.mkdir(parents=True, exist_ok=True)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.bind(("0.0.0.0", args.port))

    print(f"🎯 Listening on UDP port {args.port}")
    print(f"📁 Watch dir: {watch_dir}")
    print(f"   Ctrl+C to stop\n")

    count = 0
    while True:
        try:
            data, addr = sock.recvfrom(65535)
            if len(data) < 50:  # too small to be a valid .torrent
                print(f"⚠️  Ignored {len(data)} bytes from {addr[0]} (too small)")
                continue

            count += 1
            ts = time.strftime("%Y%m%d_%H%M%S")
            filename = f"lostfilm_{ts}_{count}.torrent"
            filepath = watch_dir / filename
            filepath.write_bytes(data)

            print(f"✅ [{ts}] Received {len(data):,} bytes from {addr[0]} → {filename}")

        except KeyboardInterrupt:
            print(f"\n🛑 Stopped. Received {count} torrents total.")
            break
        except Exception as e:
            print(f"❌ Error: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
