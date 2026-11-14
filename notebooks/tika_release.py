# /// script
# requires-python = ">=3.11,<3.15"
# dependencies = ["jdk4py==21.0.8.2"]
# ///
"""Download a pinned public release JAR, verify its SHA256, and exec Java."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import os
import re
import json
import subprocess
import shutil
import sys
import time
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.parse import quote
from urllib.request import urlopen


def sha256(path: Path) -> str:
    """Hash a cached artifact before using it."""
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def pinned_jar(repository: str, tag: str, asset: str, digest: str) -> Path:
    """Return only a release artifact matching the visible digest pin."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", repository):
        raise ValueError("repository must be owner/name")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]*", tag):
        raise ValueError("tag must be a literal release tag")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]*\.jar", asset):
        raise ValueError("asset must be a release JAR filename")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("digest must be a full lowercase SHA256")
    directory = (
        Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
        / "vgi-notebook-jars"
        / digest
    )
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / asset
    with (directory / ".lock").open("a") as lock:
        deadline = time.monotonic() + 240
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Timed out waiting for another JAR download: {directory}"
                    ) from None
                time.sleep(0.1)
        if target.exists() and sha256(target) == digest:
            return target
        url = f"https://github.com/{repository}/releases/download/{quote(tag, safe='')}/{quote(asset, safe='')}"
        print(f"Fetching pinned {repository}@{tag}/{asset}", file=sys.stderr)
        temporary = None
        try:
            with NamedTemporaryFile(dir=directory, delete=False) as output:
                temporary = Path(output.name)
                with urlopen(url, timeout=90) as response:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
            if sha256(temporary) != digest:
                raise RuntimeError("Downloaded JAR SHA256 differs from the pin; refusing to run it")
            temporary.replace(target)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    return target


RELEASES = {
    "v0.4.0": {
        "repository": "Query-farm/vgi-tika",
        "asset": "vgi-tika-v0.4.0.jar",
        "sha256": "4f65055a0c97e7e94a90377bc6b469ee2d955440135e23c049112e12d3affa61",
    }
}


def managed_java() -> str:
    """Use only the pinned managed JRE and verify its actual binary version."""
    from jdk4py import JAVA, JAVA_VERSION

    expected = (21, 0, 8)
    if tuple(JAVA_VERSION) != expected:
        raise RuntimeError("The managed Java package differs from the reviewed Java 21.0.8 pin.")
    result = subprocess.run(
        [str(JAVA), "-version"], capture_output=True, text=True, check=True, timeout=15
    )
    version = re.search(r'version "([0-9.]+)', result.stderr + result.stdout)
    if version is None or tuple(map(int, version.group(1).split("."))) != expected:
        raise RuntimeError("The actual managed Java binary is not the required Java 21.0.8.")
    return str(JAVA)


def main() -> None:
    """Launch only a reviewed release under managed Java, independent of system Java."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-runtime", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("release", choices=sorted(RELEASES))
    parser.add_argument("worker_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    java = managed_java()
    if args.check_runtime:
        print(
            json.dumps(
                {
                    "managed_runtime": "Java 21.0.8",
                    "package": "jdk4py==21.0.8.2",
                    "system_java_used": False,
                }
            )
        )
        return
    release = RELEASES[args.release]
    jar = pinned_jar(release["repository"], args.release, release["asset"], release["sha256"])
    if args.verify_only:
        print(
            json.dumps(
                {"release": args.release, "sha256": sha256(jar), "managed_runtime": "Java 21.0.8"}
            )
        )
        return
    os.execv(java, [java, "-Djava.awt.headless=true", "-jar", str(jar), *args.worker_args])


if __name__ == "__main__":
    main()
