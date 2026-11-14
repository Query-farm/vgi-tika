"""Download a pinned public release JAR, verify its SHA256, and exec Java."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import os
import re
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


def main() -> None:
    """Replace the launcher with Java so worker stdio and signals pass through."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("tag")
    parser.add_argument("asset")
    parser.add_argument("digest")
    parser.add_argument("worker_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("Install Java 21+ and put java on PATH; see README.")
    jar = pinned_jar(args.repository, args.tag, args.asset, args.digest)
    os.execv(java, [java, "-Djava.awt.headless=true", "-jar", str(jar), *args.worker_args])


if __name__ == "__main__":
    main()
