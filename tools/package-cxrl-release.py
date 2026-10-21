"""Split a fully verified APK into GitHub-sized transport parts; APK bytes stay unchanged."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil


def package(apk, output, revision, version="0.4.2", version_code=7):
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("A full source commit is required")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or version_code <= 0:
        raise ValueError("Invalid version")
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"version": version, "version_code": version_code, "apk_name": f"phone-l-speech-v{version}.apk",
                "app_id": "com.leqi.experiment.phonel", "weights_included": True,
                "source_revision": revision,
                "model": "gemma-4-E2B-it.litertlm",
                "model_sha256": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                "apk_bytes": apk.stat().st_size, "parts": []}
    total = hashlib.sha256()
    with apk.open("rb") as source:
        index = 1
        while first := source.read(1024 * 1024):
            name = manifest["apk_name"] + f".part{index:02}"
            digest = hashlib.sha256()
            size = 0
            with (output / name).open("wb") as target:
                chunk = first
                while chunk:
                    target.write(chunk)
                    digest.update(chunk)
                    total.update(chunk)
                    size += len(chunk)
                    if size == 1536 * 1024 ** 2:
                        break
                    chunk = source.read(min(1024 * 1024, 1536 * 1024 ** 2 - size))
            manifest["parts"].append({"name": name, "bytes": size, "sha256": digest.hexdigest()})
            index += 1
    manifest["apk_sha256"] = total.hexdigest()
    (output / f"manifest-v{version}.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    shutil.copyfile(Path(__file__).with_name("restore-cxrl-apk.ps1"), output / "restore-cxrl-apk.ps1")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--version", default="0.4.2")
    parser.add_argument("--version-code", type=int, default=7)
    args = parser.parse_args()
    print(json.dumps(package(args.apk, args.output, args.revision, args.version, args.version_code), indent=2))
