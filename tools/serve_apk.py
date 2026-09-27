#!/usr/bin/env python3
"""Serves the latest Parity APKs on the local network so a phone can download and install them.

Usage: python3 tools/serve_apk.py [port]
Only the dist/ folder is served. Stop with Ctrl+C.
"""
import html
import http.server
import shutil
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
RELEASE = ROOT / "androidApp/build/outputs/apk/release"
VERSION = "0.1.0"
ABIS = [
    ("arm64-v8a", "Most phones (2017 and newer)"),
    ("armeabi-v7a", "Older 32-bit phones"),
]


def lan_ip() -> str:
    for iface in ("en0", "en1", "en2", "en3", "en4", "en5", "en6", "en7", "en8"):
        ip = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True).stdout.strip()
        if ip:
            return ip
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("192.0.2.1", 9))  # no packets are sent; picks the outbound interface
        return s.getsockname()[0]


def prepare() -> list[tuple[str, str, int]]:
    DIST.mkdir(exist_ok=True)
    for old in DIST.glob("*.apk"):
        old.unlink()
    files = []
    for abi, label in ABIS:
        src = RELEASE / f"androidApp-{abi}-release.apk"
        if not src.exists():
            continue
        name = f"Parity-{VERSION}-{abi}.apk"
        shutil.copy2(src, DIST / name)
        files.append((name, label, src.stat().st_size))
    if not files:
        sys.exit("No release APKs found. Run: ./gradlew :androidApp:assembleRelease")
    rows = "\n".join(
        f'<a class="apk" href="{html.escape(n)}"><span>{html.escape(label)}</span>'
        f"<small>{html.escape(n)} · {size / 1_000_000:.0f} MB</small></a>"
        for n, label, size in files
    )
    (DIST / "index.html").write_text(PAGE.replace("{{ROWS}}", rows), encoding="utf-8")
    return files


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Parity</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #0B0D10; color: #ECEEF1; font: 17px/1.5 system-ui, sans-serif; }
  main { max-width: 520px; margin: 0 auto; padding: 40px 20px; }
  h1 { font-size: 34px; margin: 0 0 4px; }
  p { color: #9BA3AE; margin: 0 0 24px; }
  .apk { display: block; background: #7C8CFF; color: #0B0D10; text-decoration: none; border-radius: 999px;
         padding: 16px 24px; margin: 0 0 12px; font-weight: 600; }
  .apk small { display: block; font-weight: 400; opacity: .75; font-size: 13px; }
  .apk + .apk { background: #1D2127; color: #ECEEF1; border: 1px solid #2A2F37; }
  ol { color: #9BA3AE; padding-left: 20px; }
  .tri { color: #2BD67B; } .tri.dn { color: #FF5A5F; }
</style></head>
<body><main>
  <h1><span class="tri">▲</span><span class="tri dn">▼</span> Parity</h1>
  <p>Tap to download, then open the file to install.</p>
  {{ROWS}}
  <ol>
    <li>If Android blocks it, allow <b>Install unknown apps</b> for your browser when asked.</li>
    <li>Installing over an earlier Parity keeps your data.</li>
  </ol>
</main></body></html>
"""


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".apk": "application/vnd.android.package-archive",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DIST), **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8420
    files = prepare()
    url = f"http://{lan_ip()}:{port}/"
    print(f"Serving {', '.join(f for f, _, _ in files)}")
    print(f"Open on your phone: {url}", flush=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
