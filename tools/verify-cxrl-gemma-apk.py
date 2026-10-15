"""Verify that a complete release APK embeds the pinned Gemma GPU weights."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

EXPECTED_HASH = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"
EXPECTED_BYTES = 2008432640


def verify(path):
    with zipfile.ZipFile(path) as apk:
        manifest = json.loads(apk.read("assets/gemma/manifest.json"))
        if manifest["sha256"] != EXPECTED_HASH or manifest["bytes"] != EXPECTED_BYTES:
            raise ValueError("Unexpected model manifest")
        if len({part["name"] for part in manifest["parts"]}) != len(manifest["parts"]):
            raise ValueError("Duplicate weight part")
        digest = hashlib.sha256()
        size = 0
        for part in manifest["parts"]:
            entry = apk.getinfo("assets/gemma/" + part["name"])
            if entry.file_size != part["bytes"] or entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError("Truncated or compressed model part")
            with apk.open(entry) as source:
                while chunk := source.read(4 * 1024 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
        if size != EXPECTED_BYTES or digest.hexdigest() != EXPECTED_HASH:
            raise ValueError("Packaged model SHA-256 mismatch")
        for required in ("lib/arm64-v8a/liblitertlm_jni.so", "assets/gemma-vision-prompt.txt",
                         "assets/licenses/NOTICE.txt", "assets/licenses/Apache-2.0.txt"):
            apk.getinfo(required)
        return {"apk_bytes": Path(path).stat().st_size, "weights_bytes": size,
                "model_sha256": digest.hexdigest(), "model_parts": len(manifest["parts"])}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python tools/verify-cxrl-gemma-apk.py <complete.apk>")
    print(json.dumps(verify(sys.argv[1]), indent=2))
