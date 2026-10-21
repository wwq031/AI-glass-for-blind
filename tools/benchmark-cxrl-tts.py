"""Fixed public-text CPU comparison; measures audio readiness, not actual Android first sound."""
import json
from pathlib import Path
import time
import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'artifacts/models/speech/melo'
PHRASES = ['你好，我是你的离线语音助手。', '我可以回答问题，也可以朗读已经识别的图片描述。',
           '拍照前需要你在手机上确认，遇到不确定的内容，我会如实说明。']


def create(threads):
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=str(MODEL / 'model.int8.onnx'), lexicon=str(MODEL / 'lexicon.txt'),
            tokens=str(MODEL / 'tokens.txt'), dict_dir=str(MODEL / 'dict')), num_threads=threads),
        rule_fsts=','.join(str(MODEL / n) for n in ['date.fst', 'number.fst', 'phone.fst']),
        max_num_sentences=1))


def generate(tts, text):
    start = time.monotonic()
    audio = tts.generate(text, sid=0, speed=1.0)
    assert audio.sample_rate == 44100 and len(audio.samples) > 0
    assert np.isfinite(audio.samples).all() and np.max(np.abs(audio.samples)) > 0.00001
    return {'seconds': round(time.monotonic() - start, 4),
            'audio_seconds': round(len(audio.samples) / audio.sample_rate, 4)}


def main():
    rows = []
    for threads in [2, 4]:
        tts = create(threads)
        for trial in range(3):
            full = generate(tts, ''.join(PHRASES))
            pieces = [generate(tts, phrase) for phrase in PHRASES]
            rows.append({'threads': threads, 'trial': trial + 1,
                         'full_audio_ready_seconds': full['seconds'],
                         'first_phrase_ready_seconds': pieces[0]['seconds'],
                         'phrase_synthesis_total_seconds': round(sum(p['seconds'] for p in pieces), 4),
                         'full_audio_seconds': full['audio_seconds'], 'phrase_results': pieces})
        del tts
    result = {'runtime': 'sherpa-onnx:1.13.8', 'scope': 'desktop CPU, public fixed text; not Android first sound',
              'public_phrases': PHRASES, 'rows': rows}
    output = ROOT / 'artifacts/issue-work/tts-latency-benchmark.json'
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
