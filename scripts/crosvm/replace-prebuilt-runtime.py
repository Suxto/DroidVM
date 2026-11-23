#!/usr/bin/env python3
"""Embed the camera-enabled runtime in the ARM64 prebuilts used by Gradle."""

import argparse
import hashlib
import json
import tarfile
from pathlib import Path
from tempfile import TemporaryDirectory


def replace_runtime(directory: Path, runtime: Path) -> None:
    stem = "prebuilt-arm64-v8a"
    runtime_name = "usr/bin/crosvm"
    archive = directory / f"{stem}.tar.xz"
    metadata = directory / f"{stem}.json"
    manifest = json.loads(metadata.read_text(encoding="utf-8"))
    (entry,) = [item for item in manifest["hash"] if item["file"] == runtime_name]
    count = 0

    with TemporaryDirectory(dir=directory) as temporary:
        output = Path(temporary) / archive.name
        new_metadata = Path(temporary) / metadata.name
        with tarfile.open(archive, "r|xz") as source, tarfile.open(output, "w:xz", preset=6) as target:
            for member in source:
                if member.name == runtime_name:
                    if not member.isfile():
                        raise ValueError(f"{runtime_name} is not a regular file")
                    with source.extractfile(member) as old_runtime:
                        if hashlib.file_digest(old_runtime, "sha256").hexdigest() != entry["sha256"]:
                            raise ValueError(f"Archive and manifest disagree for {runtime_name}")
                    with runtime.open("rb") as data:
                        entry["sha256"] = hashlib.file_digest(data, "sha256").hexdigest()
                        data.seek(0)
                        member.size = runtime.stat().st_size
                        target.addfile(member, data)
                    count += 1
                elif member.isfile():
                    with source.extractfile(member) as data:
                        target.addfile(member, data)
                else:
                    target.addfile(member)
        if count != 1:
            raise ValueError(f"Expected exactly one {runtime_name} in {archive}, found {count}")
        new_metadata.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        output.replace(archive)
        new_metadata.replace(metadata)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prebuilts", type=Path)
    parser.add_argument("crosvm", type=Path)
    args = parser.parse_args()
    replace_runtime(args.prebuilts, args.crosvm)
