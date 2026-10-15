"""Real CPU inference using the same pinned speech models; not Android hardware QA."""
import json
import sys
import time
import wave
from pathlib import Path
import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'artifacts/models/speech'


def main():
    started = time.monotonic()
    recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(MODELS / 'sensevoice/model.int8.onnx'),
        tokens=str(MODELS / 'sensevoice/tokens.txt'), num_threads=2, use_itn=True)

    def transcribe(samples):
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, samples)
        recognizer.decode_stream(stream)
        return stream.result.text

    with wave.open(str(MODELS / 'sensevoice/zh.wav')) as source:
        assert source.getnchannels() == 1 and source.getsampwidth() == 2 and source.getframerate() == 16000
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    transcript = transcribe(samples)
    assert '早上' in transcript and '下午' in transcript, transcript
    model = MODELS / 'melo'
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=str(model / 'model.int8.onnx'), lexicon=str(model / 'lexicon.txt'),
            tokens=str(model / 'tokens.txt'), dict_dir=str(model / 'dict')), num_threads=2),
        rule_fsts=','.join(str(model / name) for name in ['date.fst', 'number.fst', 'phone.fst'])))
    audio = tts.generate('你好，离线中文语音播报测试。现在是二零二六年。', sid=0, speed=1.0)
    assert audio.sample_rate == 44100 and len(audio.samples) > audio.sample_rate
    assert np.isfinite(audio.samples).all() and np.max(np.abs(audio.samples)) > 0.01
    resampled = np.interp(np.arange(0, len(audio.samples), audio.sample_rate / 16000),
                          np.arange(len(audio.samples)), audio.samples).astype(np.float32)
    loopback = transcribe(resampled)
    assert '语音' in loopback and '测试' in loopback, loopback
    destination = ROOT / 'artifacts/issue-work'
    destination.mkdir(parents=True, exist_ok=True)
    with wave.open(str(destination / 'speech-native-test.wav'), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(audio.sample_rate)
        output.writeframes((np.clip(audio.samples, -1, 1) * 32767).astype(np.int16).tobytes())
    report = {'runtime': 'sherpa-onnx:1.13.8', 'platform': sys.platform,
              'scope': 'real CPU ASR/TTS and loopback; not Android microphone/GPU/Bluetooth acceptance',
              'asr_public_sample_text': transcript, 'tts_loopback_text': loopback,
              'tts_sample_rate': audio.sample_rate, 'tts_seconds': len(audio.samples) / audio.sample_rate,
              'elapsed_seconds': round(time.monotonic() - started, 2)}
    (destination / 'speech-native-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
