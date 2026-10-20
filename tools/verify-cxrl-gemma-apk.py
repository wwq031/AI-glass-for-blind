"""Verify that a complete release APK embeds the pinned multimodal Gemma weights."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

EXPECTED_HASH = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
EXPECTED_BYTES = 2588147712


def verify_multimodal_header(source):
    """Bounded metadata check, in addition to the full pinned SHA-256 below.

    Official LiteRT-LM header: magic[8], version[12], padding[4], end[8],
    FlatBuffer section directory. No multi-GB model allocation is needed.
    """
    prefix = source.read(32)
    if len(prefix) != 32 or prefix[:8] != b"LITERTLM":
        raise ValueError("Invalid LiteRT-LM model header")
    end = struct.unpack_from("<Q", prefix, 24)[0]
    if not 32 < end <= 1024 * 1024:
        raise ValueError("Invalid LiteRT-LM directory bounds")
    directory = source.read(end - 32)
    if len(directory) != end - 32:
        raise ValueError("Truncated model directory")
    required = (b"tf_lite_vision_encoder", b"tf_lite_vision_adapter", b"tf_lite_prefill_decode")
    if any(kind not in directory for kind in required):
        raise ValueError("Complete text/vision sections required; text-only GPU weights are unsupported")
    return [kind.decode("ascii") for kind in required]


def verify(path):
    with zipfile.ZipFile(path) as apk:
        manifest = json.loads(apk.read("assets/gemma/manifest.json"))
        if manifest["sha256"] != EXPECTED_HASH or manifest["bytes"] != EXPECTED_BYTES:
            raise ValueError("Unexpected model manifest")
        if len({part["name"] for part in manifest["parts"]}) != len(manifest["parts"]):
            raise ValueError("Duplicate weight part")
        with apk.open("assets/gemma/" + manifest["parts"][0]["name"]) as first:
            model_types = verify_multimodal_header(first)
        digest = hashlib.sha256()
        size = 0
        for part in manifest["parts"]:
            entry = apk.getinfo("assets/gemma/" + part["name"])
            if entry.file_size != part["bytes"] or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError("Truncated model part or unsupported ZIP compression")
            with apk.open(entry) as source:
                while chunk := source.read(4 * 1024 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
        if size != EXPECTED_BYTES or digest.hexdigest() != EXPECTED_HASH:
            raise ValueError("Packaged model SHA-256 mismatch")
        for required in ("lib/arm64-v8a/liblitertlm_jni.so", "assets/gemma-vision-prompt.txt",
                         "assets/licenses/NOTICE.txt", "assets/licenses/Apache-2.0.txt"):
            apk.getinfo(required)
        report = {"apk_bytes": Path(path).stat().st_size, "weights_bytes": size,
                  "model_sha256": digest.hexdigest(), "model_parts": len(manifest["parts"]),
                  "required_model_types": model_types}
        if "assets/speech/manifest.json" in apk.namelist():
            speech = json.loads(apk.read("assets/speech/manifest.json"))
            lock = json.loads(Path(__file__).with_name('cxrl-speech-models.lock.json').read_text())
            if speech != lock:
                raise ValueError('Speech metadata does not match pinned model lock')
            for item in speech['files']:
                entry = apk.getinfo('assets/speech/files/' + item['name'])
                with apk.open(entry) as source:
                    actual = hashlib.file_digest(source, 'sha256').hexdigest()
                if entry.file_size != item['bytes'] or actual != item['sha256']:
                    raise ValueError('Packaged speech model mismatch: ' + item['name'])
            apk.getinfo('lib/arm64-v8a/libsherpa-onnx-jni.so')
            report['speech_files'] = len(speech['files'])
            report['speech_runtime'] = speech['runtime']['version']
            report['asr'] = speech['asr']['model']
            report['tts'] = speech['tts']['model']
        return report


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python tools/verify-cxrl-gemma-apk.py <complete.apk>")
    print(json.dumps(verify(sys.argv[1]), indent=2))
