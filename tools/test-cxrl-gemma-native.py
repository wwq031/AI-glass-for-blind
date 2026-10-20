"""Real desktop regression for the release model, not an Android hardware test.

Requires pip install litert-lm==0.17.1. Uses a pinned public upstream apple image;
never reads a tester's photo. See phone-l/README.md for invocation.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.request

import litert_lm as lm
from litert_lm_builder import litertlm_peek

MODEL_HASH = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
IMAGE_HASH = "68c395916620d6048138ad6e15975154d3bd7728efe13f05449df7bed1cebc59"
IMAGE_URL = ("https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/"
             "5e58e9a0aef7abf7091207a8b1d1063a1c800f08/support/preprocessor/testdata/apple.bmp")
ROOT = Path(__file__).resolve().parents[1]


def model_types(path):
    header = litertlm_peek.read_litertlm_header(str(path), io.StringIO()).SectionMetadata()
    return [kind for index in range(header.ObjectsLength())
            if (kind := litertlm_peek.get_model_type(header.Objects(index)))]


def engine(path, cache):
    cache.mkdir(parents=True, exist_ok=True)
    return lm.Engine(str(path.resolve()), backend=lm.Backend.GPU(), vision_backend=lm.Backend.GPU(),
                     max_num_tokens=4096, max_num_images=1, cache_dir=str(cache))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--legacy-model", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/issue-work/gemma-native-result.json")
    args = parser.parse_args()
    with args.model.open("rb") as source:
        assert hashlib.file_digest(source, "sha256").hexdigest() == MODEL_HASH, "Wrong release weights"
    types = model_types(args.model)
    assert "tf_lite_vision_encoder" in types, f"No vision encoder: {types}"
    cache = args.output.parent / "gemma-native-cache"
    cache.mkdir(parents=True, exist_ok=True)
    report = {"model_sha256": MODEL_HASH, "model_types": types,
              "platform": "desktop GPU, not Android hardware", "runs": []}
    if args.legacy_model:
        old_types = model_types(args.legacy_model)
        assert "tf_lite_vision_encoder" not in old_types
        with engine(args.legacy_model, cache / "legacy") as old:
            try:
                with old.create_conversation(max_output_tokens=1024):
                    pass
            except RuntimeError:
                report["legacy_missing_vision_reproduced"] = True
            else:
                raise AssertionError("Text-only model unexpectedly accepted a vision conversation")
    image_path = args.output.parent / "gemma-public-apple.bmp"
    if not image_path.exists():
        urllib.request.urlretrieve(IMAGE_URL, image_path)
    image = image_path.read_bytes()
    assert hashlib.sha256(image).hexdigest() == IMAGE_HASH, "Wrong public fixture"
    prompt = (ROOT / "apps/device-experiment/android/phone-l/src/main/assets/gemma-vision-prompt.txt").read_text(encoding="utf-8")
    with engine(args.model, cache / "multimodal") as active:
        # Same lazy-resource readiness gate used by Android, with no user message.
        with active.create_conversation(max_output_tokens=1024):
            pass
        report["vision_readiness_check"] = "passed"
        for index in range(3):
            started = time.monotonic()
            with active.create_conversation(
                system_message="你是运行在本机的通用视觉模型。严格按用户消息里的要求作答，不要自行假设画面是什么场景，也不要添加用户没有要求的内容。",
                max_output_tokens=1024,
                sampler_config=lm.SamplerConfig(top_k=1, top_p=1.0, temperature=1.0, seed=0),
            ) as conversation:
                reply = conversation.send_message(lm.Contents.of(lm.Content.ImageBytes(image), lm.Content.Text(prompt)))
                raw = "".join(part["text"] for part in reply.get("content", []) if part.get("type") == "text")
            result = json.loads(raw)
            assert set(result) == {"summary"} and isinstance(result["summary"], str), raw
            assert 1 <= len(result["summary"].strip()) <= 160
            assert "苹果" in result["summary"], raw
            report["runs"].append({"run": index + 1, "seconds": round(time.monotonic() - started, 3),
                                   "public_fixture_summary": result["summary"]})
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
