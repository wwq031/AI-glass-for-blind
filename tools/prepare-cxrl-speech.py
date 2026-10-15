"""Download pinned speech weights/runtime; files stay outside Git."""
import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'tools/cxrl-speech-models.lock.json').read_text())


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def download(urls, path, expected, size=None):
    if path.is_file() and sha(path) == expected:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + '.part')
    for url in urls:
        try:
            with urllib.request.urlopen(url, timeout=120) as source, partial.open('wb') as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
            if sha(partial) != expected or (size is not None and partial.stat().st_size != size):
                raise ValueError('Checksum/size mismatch: ' + path.name)
            partial.replace(path)
            return
        except Exception as error:
            last_error = error
    raise last_error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-only', action='store_true')
    args = parser.parse_args()
    runtime = LOCK['runtime']
    download([runtime['url']], ROOT / 'apps/phone-companion/android/libs/sherpa-onnx-1.13.8.aar',
             runtime['sha256'], runtime['bytes'])
    if args.runtime_only:
        return
    base = ROOT / 'artifacts/models/speech'
    archive = base / 'sensevoice.tar.bz2'
    download([LOCK['asr']['archive_url']], archive, LOCK['asr']['archive_sha256'])
    with tarfile.open(archive) as source:
        sample = next(m for m in source.getmembers() if m.isfile() and m.name.endswith('/test_wavs/zh.wav'))
        sample_path = base / 'sensevoice/zh.wav'
        sample_path.parent.mkdir(parents=True, exist_ok=True)
        with source.extractfile(sample) as input_file, sample_path.open('wb') as output:
            shutil.copyfileobj(input_file, output)
        for item in LOCK['files']:
            if not item['name'].startswith('sensevoice/'):
                continue
            matches = [m for m in source.getmembers() if m.isfile() and Path(m.name).name == Path(item['name']).name]
            if len(matches) != 1:
                raise ValueError('Ambiguous archive entry')
            target = base / item['name']
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(matches[0]) as input_file, target.open('wb') as output:
                shutil.copyfileobj(input_file, output, 1024 * 1024)
    tts = LOCK['tts']
    for item in LOCK['files']:
        target = base / item['name']
        if item['name'].startswith('melo/'):
            relative = item['name'].removeprefix('melo/')
            urls = [f'https://{host}/{tts["repository"]}/resolve/{tts["revision"]}/{relative}'
                    for host in ['huggingface.co', 'hf-mirror.com']]
            download(urls, target, item['sha256'], item['bytes'])
        if target.stat().st_size != item['bytes'] or sha(target) != item['sha256']:
            raise ValueError('Invalid speech asset: ' + item['name'])
    print('Verified speech runtime and all model files')


if __name__ == '__main__':
    main()
