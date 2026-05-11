#!/usr/bin/env python3
"""
Local dev server for FisionTV+ Network Certifier STB self-update.

Replaces the `adb install` loop for iterating on the app. After a
one-time bootstrap install of any build that contains the self-update
client (everything on `main` after commit 8eda9f5), every subsequent
change is delivered to the STB through the same /v1/app/version
manifest + APK-download endpoints the production app will use — so
you exercise the exact code path that will run in the field.

Usage:

    # First iteration of a session (rebuild + serve)
    python3 scripts/dev-update-server.py --bump

    # Subsequent iterations (re-bump versionCode, rebuild, re-serve)
    python3 scripts/dev-update-server.py --bump

    # Already built? just serve.
    python3 scripts/dev-update-server.py --serve-only

    # Make the update optional rather than forced
    python3 scripts/dev-update-server.py --bump --optional

Bootstrap (one-time per STB, after you pull a branch that adds the
self-update client for the first time):

    JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \\
        ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

After that the STB knows how to fetch /v1/app/version and you can
iterate via this script alone.

Workflow per change:

    1. Make your code change on the dev Mac.
    2. python3 scripts/dev-update-server.py --bump
    3. Relaunch the app on the STB (or wait for its next launch — the
       manifest fetch fires in AppContainer.init).
    4. The new version's banner appears. Tap "Update & run".
    5. Update downloads, the system "Install update?" dialog shows,
       tap Install. App relaunches; the cert auto-resumes (because
       MainViewModel.startUpdate persisted the resume flag).

Routes served:
    GET /v1/app/version              — the manifest JSON
    GET /v1/app/download/<file>      — the APK bytes

The debug build's APP_UPDATE_URL is `http://192.168.10.233:8080/v1/app/version`
(set in app/build.gradle.kts). Make sure this script binds to that IP
and port — pass `--host 192.168.10.233 --port 8080` if auto-detect
picks a different interface.

Integrity:
    - apkSha256 over the bytes written into the APK file
    - signingCertSha256 from apksigner (preferred) or keytool (fallback)
    - Debug builds set BuildConfig.APP_SIGNING_CERT_SHA256 = "" so the
      compile-time pin is skipped; the manifest's signing-cert hash
      still has to match the actual APK signature, which the script
      computes from the same file the STB downloads. They match by
      construction.

State:
    scripts/.dev-update-state.json tracks the most recent versionCode
    so `--bump` is monotonic across runs. Delete it to reset to 1001.
"""

import argparse
import hashlib
import http.server
import json
import os
import re
import shutil
import socket
import socketserver
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
APK_PATH = REPO_ROOT / "app/build/outputs/apk/debug/app-debug.apk"
VERSION_STATE_FILE = REPO_ROOT / "scripts/.dev-update-state.json"
SERVE_ROOT = REPO_ROOT / "scripts/.serve-root"


# ──────────────────────────────────────────────────────────────────────
# Detection helpers
# ──────────────────────────────────────────────────────────────────────

def detect_local_ip() -> str:
    """Best-effort detection of the LAN IP the STB can reach."""
    # Connect a UDP socket to a public IP; the kernel picks the right
    # local interface, then we read back the bound IP. Nothing is sent.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()


def find_apksigner() -> str | None:
    """Locate apksigner from Android SDK build-tools, or fall back to PATH."""
    if shutil.which("apksigner"):
        return "apksigner"
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "Library/Android/sdk"),
        "/opt/homebrew/share/android-sdk",
    ]
    for sdk in filter(None, candidates):
        bt = Path(sdk) / "build-tools"
        if not bt.is_dir():
            continue
        # Newest build-tools first.
        for version_dir in sorted(bt.iterdir(), reverse=True):
            tool = version_dir / "apksigner"
            if tool.is_file() and os.access(tool, os.X_OK):
                return str(tool)
    return None


# ──────────────────────────────────────────────────────────────────────
# Build + hash
# ──────────────────────────────────────────────────────────────────────

def bump_version_state() -> tuple[int, str]:
    """Returns (versionCode, versionName) for the next build."""
    if VERSION_STATE_FILE.exists():
        state = json.loads(VERSION_STATE_FILE.read_text())
    else:
        state = {"code": 1000}
    state["code"] = int(state.get("code", 1000)) + 1
    # Synthesise a versionName like "0.0.0-dev.1001" so the STB UI
    # surfaces something distinguishable per build.
    state["name"] = f"0.0.0-dev.{state['code']}"
    VERSION_STATE_FILE.write_text(json.dumps(state, indent=2))
    return state["code"], state["name"]


def current_version_state() -> tuple[int, str]:
    if VERSION_STATE_FILE.exists():
        state = json.loads(VERSION_STATE_FILE.read_text())
        return state["code"], state.get("name", f"0.0.0-dev.{state['code']}")
    return 1, "0.0.0-local"


def build_apk(version_code: int, version_name: str) -> None:
    env = os.environ.copy()
    env["FISION_VERSION_CODE"] = str(version_code)
    env["FISION_VERSION_NAME"] = version_name
    if "JAVA_HOME" not in env:
        # Default for this checkout (see ~/.zshrc instruction in the
        # README). Don't fail loudly — the dev's setup might differ.
        guess = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        if Path(guess).exists():
            env["JAVA_HOME"] = guess
    print(f"==> ./gradlew assembleDebug   (code={version_code}, name={version_name})")
    subprocess.run(
        ["./gradlew", "assembleDebug"],
        cwd=REPO_ROOT, env=env, check=True
    )


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def signing_cert_sha256(apk: Path) -> str:
    """Extract the APK's signing certificate SHA-256 in hex (lowercase, no colons).

    apksigner's wrapper invokes `java` from PATH. If the caller hasn't
    set JAVA_HOME we try to discover an openjdk@17 install and inject
    both JAVA_HOME and JAVA_HOME/bin into the subprocess env, so the
    script works on a fresh terminal without the dev sourcing
    ~/.zshrc first.
    """
    env = os.environ.copy()
    if not env.get("JAVA_HOME") or not Path(env["JAVA_HOME"]).exists():
        for c in (
            "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
            "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
        ):
            if Path(c).exists():
                env["JAVA_HOME"] = c
                env["PATH"] = f"{c}/bin:" + env.get("PATH", "")
                break

    # apksigner (preferred — handles all signing schemes v1/v2/v3/v4).
    apksigner = find_apksigner()
    if apksigner:
        try:
            out = subprocess.run(
                [apksigner, "verify", "--print-certs", str(apk)],
                capture_output=True, text=True, check=True, env=env,
            ).stdout
            # apksigner prints lines like:
            #   "V2 Signer: certificate SHA-256 digest: <64 hex>"      (older build-tools)
            #   "Signer #1 certificate SHA-256 digest: <64 hex>"       (newer)
            # Match either.
            m = re.search(
                r"(?:V\d+ Signer|Signer #\d+).*?SHA-?256 digest:\s*([0-9a-fA-F]{64})",
                out,
            )
            if m:
                return m.group(1).lower()
        except subprocess.CalledProcessError as e:
            print(f"   apksigner failed: {e.stderr or e.stdout}", file=sys.stderr)

    # Fallback to keytool — works for v1-signed APKs; debug keystore
    # signs with v1+v2 so this still extracts a usable cert.
    try:
        out = subprocess.run(
            ["keytool", "-printcert", "-jarfile", str(apk)],
            capture_output=True, text=True, check=True, env=env,
        ).stdout
        m = re.search(r"SHA-?256:\s*([0-9A-Fa-f:]+)", out)
        if m:
            return m.group(1).replace(":", "").lower()
    except (FileNotFoundError, subprocess.CalledProcessError):
        pass

    raise RuntimeError(
        "Could not extract signing-cert SHA-256. Install Android SDK build-tools "
        "(apksigner) or ensure JDK keytool is on PATH (JAVA_HOME set)."
    )


# ──────────────────────────────────────────────────────────────────────
# Manifest + serve
# ──────────────────────────────────────────────────────────────────────

def write_manifest(serve_root: Path, *,
                   version_code: int, version_name: str,
                   apk_filename: str, apk_size: int, apk_sha: str,
                   sig_sha: str, host: str, port: int,
                   min_required: int, release_notes: str) -> dict:
    manifest = {
        "schemaVersion": 1,
        "latestVersionName": version_name,
        "latestVersionCode": version_code,
        "minRequiredVersionCode": min_required,
        "apkUrl": f"http://{host}:{port}/v1/app/download/{apk_filename}",
        "apkSizeBytes": apk_size,
        "apkSha256": apk_sha,
        "signingCertSha256": sig_sha,
        "releaseNotes": release_notes,
        "publishedAt": None,
    }
    out = serve_root / "v1" / "app" / "version"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2))
    return manifest


class DevServerHandler(http.server.SimpleHTTPRequestHandler):
    """Static handler that fudges the Content-Type for the manifest path."""

    def log_message(self, fmt, *args):
        print(f"  → {self.address_string()} {fmt % args}")

    def end_headers(self):
        if self.path == "/v1/app/version":
            self.send_header("Content-Type", "application/json")
        # Helpful CORS-ish — the STB isn't a browser but if anyone curls
        # this server they shouldn't hit cache weirdness.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


# ──────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────

def main() -> int:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--host", default=None,
                   help="LAN IP the manifest's apkUrl points at "
                        "(auto-detected if omitted).")
    p.add_argument("--bump", action="store_true",
                   help="Bump versionCode and rebuild the APK before serving.")
    p.add_argument("--serve-only", action="store_true",
                   help="Skip the build step; serve whatever's in app/build/outputs/.")
    p.add_argument("--optional", action="store_true",
                   help="Update is advisory — keeps minRequired at 1 so the "
                        "cert button isn't blocked. Default: forced "
                        "(minRequired = latest).")
    p.add_argument("--notes", default="dev build",
                   help="releaseNotes string in the manifest (default: 'dev build').")
    args = p.parse_args()

    if args.bump:
        code, name = bump_version_state()
    else:
        code, name = current_version_state()

    if not args.serve_only:
        build_apk(code, name)

    if not APK_PATH.exists():
        print(f"!! APK not found at {APK_PATH}.", file=sys.stderr)
        print("   Run with --bump (or remove --serve-only) so the script builds it.",
              file=sys.stderr)
        return 1

    host = args.host or detect_local_ip()
    apk_size = APK_PATH.stat().st_size
    apk_sha = sha256_file(APK_PATH)
    sig_sha = signing_cert_sha256(APK_PATH)
    apk_filename = f"app-debug-{code}.apk"

    # Stage the serve directory shaped like the production API tree.
    SERVE_ROOT.mkdir(parents=True, exist_ok=True)
    download_dir = SERVE_ROOT / "v1" / "app" / "download"
    download_dir.mkdir(parents=True, exist_ok=True)
    # Cheap copy — APK is ~12 MB, no need to symlink (which http.server
    # doesn't follow by default on some Python builds).
    shutil.copyfile(APK_PATH, download_dir / apk_filename)

    min_required = code if not args.optional else 1
    manifest = write_manifest(
        SERVE_ROOT,
        version_code=code, version_name=name,
        apk_filename=apk_filename, apk_size=apk_size, apk_sha=apk_sha,
        sig_sha=sig_sha, host=host, port=args.port,
        min_required=min_required, release_notes=args.notes,
    )

    print()
    print(f"==> ready")
    print(f"    serve root          : {SERVE_ROOT}")
    print(f"    manifest URL        : http://{host}:{args.port}/v1/app/version")
    print(f"    apk URL             : http://{host}:{args.port}/v1/app/download/{apk_filename}")
    print(f"    versionCode         : {code}")
    print(f"    versionName         : {name}")
    print(f"    minRequired         : {min_required}  ({'FORCED' if not args.optional else 'optional'})")
    print(f"    apkSize             : {apk_size:,} bytes")
    print(f"    apkSha256           : {apk_sha}")
    print(f"    signingCertSha256   : {sig_sha}")
    print()
    print("Press Ctrl+C to stop.")
    print()

    os.chdir(SERVE_ROOT)
    socketserver.TCPServer.allow_reuse_address = True
    try:
        with socketserver.TCPServer(("", args.port), DevServerHandler) as httpd:
            httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n→ stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
