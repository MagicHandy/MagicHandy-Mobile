"""Capture the pinned desktop and current mobile motion paths without connecting a device."""

import argparse
import json
import os
import re
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path

REVISION = "1b80d72ee4b90a5ab80a9bb3fe373b1640388f80"
HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent


def run(command, cwd, env):
    subprocess.run([str(arg) for arg in command], cwd=cwd, env=env, check=True)


def unpack_baseline(archive, work):
    # Historical data is input. Only copy the captured comparison records.
    with zipfile.ZipFile(archive) as source:
        for entry in source.namelist():
            parts = entry.split("/")
            if len(parts) != 2 or not re.fullmatch(r"[A-Za-z0-9_-]+\.json", parts[1]):
                continue
            if (
                parts[0] not in {"before", "wire-before"}
                and entry != "bridge/before.json"
                and entry
                not in {"verification/provenance.json", "verification/comparison.json"}
            ):
                continue
            target = (
                work
                / ("legacy-run" if parts[0] == "verification" else parts[0])
                / parts[1]
            )
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(source.read(entry))


def main():
    arg = argparse.ArgumentParser(description=__doc__)
    arg.add_argument("--desktop", type=Path, default=PROJECT.parent / "MagicHandy")
    arg.add_argument("--work", type=Path, default=PROJECT / ".work/motion-android")
    arg.add_argument(
        "--baseline-archive",
        type=Path,
        help="captures.zip from the initial Android comparison",
    )
    args = arg.parse_args()
    work = args.work.resolve()
    snapshot = work / "desktop"
    for directory in [
        snapshot,
        work / "reference",
        work / "after",
        work / "bridge",
        work / "wire",
    ]:
        directory.mkdir(parents=True, exist_ok=True)
    if args.baseline_archive:
        unpack_baseline(args.baseline_archive, work)
    env = {
        **os.environ,
        "GOTELEMETRY": "off",
        "MAGICHANDY_MOTION_REFERENCE": str(work / "reference"),
        "MAGICHANDY_MOTION_CAPTURE": str(work / "after"),
        "MAGICHANDY_WIRE_CAPTURE": str(work / "wire"),
    }
    env.pop("MAGICHANDY_MOTION_ONLY", None)
    archive = work / "desktop.tar"
    run(
        ["git", "archive", "--format=tar", "-o", archive, REVISION],
        args.desktop.resolve(),
        env,
    )
    with tarfile.open(archive) as stream:
        stream.extractall(snapshot, filter="data")
    overlay = work / "overlay.json"
    overlay.write_text(
        json.dumps(
            {
                "Replace": {
                    str(snapshot / "internal/motion" / name): str(HERE / name)
                    for name in ["desktop_capture_test.go", "desktop_bridge_test.go"]
                }
            }
        ),
        encoding="utf-8",
    )
    go = shutil.which("go")
    if go is None:
        raise SystemExit("Go is needed to run the desktop reference")
    if os.name == "nt":
        cached = (
            Path.home()
            / "go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.11.windows-amd64/bin/go.exe"
        )
        if cached.is_file():
            go = str(cached)
            env["GOTOOLCHAIN"] = "local"
        jdk = Path(os.environ["LOCALAPPDATA"]) / "Programs/Android Studio/jbr"
        if jdk.is_dir():
            env["JAVA_HOME"] = str(jdk)
    env["MAGICHANDY_LINEAR_CAPTURE"] = str(work / "bridge/desktop.json")
    run(
        [
            go,
            "test",
            "-overlay",
            overlay,
            "-tags",
            "magichandy_labs",
            "./internal/motion",
            "-run",
            "^TestPhone(Motion|Bridge)Reference$",
            "-count=1",
        ],
        snapshot,
        env,
    )
    env["MAGICHANDY_LINEAR_CAPTURE"] = str(work / "bridge/after.json")
    gradle = PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run(
        [
            gradle,
            "--offline",
            "--no-configuration-cache",
            "-I",
            HERE / "atlas.init.gradle",
            ":core:test",
            "--tests",
            "*HandyMotionAtlasTest*",
            "--tests",
            "*BridgeMotionAtlasTest*",
            ":transport:test",
            "--tests",
            "*HandyMotionCaptureTest*",
        ],
        PROJECT,
        env,
    )


if __name__ == "__main__":
    main()
