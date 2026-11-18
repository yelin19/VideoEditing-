#!/usr/bin/env python3
"""Download Nara SAR's CPU INT8 inference assets from the official Hugging Face repo."""
from __future__ import annotations
import hashlib
from pathlib import Path
import sys
import urllib.request

REPO = "https://huggingface.co/yelin201875/nara-sar/resolve/main/"
ROOT = Path(__file__).resolve().parent
MODEL_DIR = ROOT / "model"
FILES = {
    "onnx/acoustic.onnx": "f16c6e4bc3274699004bef99af18bb4be08add6ec7d8b0a036fa48210cdab058",
    "language.lm": "a3d35159009285b32973acbfea40ebc1dd03df71032b83119217f255d4ce3558",
    "sar.json": "2ccd3f72f3db378ec2a2749d99ed8e28e532a356d8ed0ab634c2962f75852571",
    "tokenizer.model": "41db3cb37ff5e55bd371b373bb3bdb3cf3daf676b3800afd4d086b611e1a0fef",
    "transcribe.py": "0ce7788cb387c91bdf9289809c5b5d691a5f26ba468ed83f4c6ddadc4d8beeb4",
    "sar_decoder.py": "34325facd721859501b32419aadb6b77f990ce4d7ef28f5ee33c7c4bb410a1b3",
    "sar_syllables.py": "09c3a770b1d2f3d2adc42a8c90124dafdb6346ceb093fa8965c79b6a9b267a94",
    "requirements.txt": "41fef69f80f8f4a61032eecbab66dccb31bcd2e21c0878b955efade2e7ec3b27",
    "LICENSE": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    "LICENSES/Meta-omniASR-LICENSE.txt": "a70a523bafbb595c2844104feb313d204904dac91c3d186c05f22a10a71c7a94",
    "LICENSES/fairseq2-MIT.txt": "da6d3703ed11cbe42bd212c725957c98da23cbff1998c05fa4b3d976d1a58e93",
    "NOTICE": "5608e30fea7207e29fdf6f5b8c9d071f74593fe8410af85df19af67597a12fb6",
    "ATTRIBUTION.md": "044693340f47e635504058a5a38244f5ef0dc0cbb05a400216776228767c11e0",
    "THIRD_PARTY.md": "03b47558b4fbe6ab539dcf77b3c9173efd07e8e37b303b788f38535d5c378246",
    "SHA256SUMS": "",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(relative: str, expected: str) -> None:
    destination = MODEL_DIR / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and (not expected or sha256(destination) == expected):
        print(f"Already verified: {relative}")
        return
    temporary = destination.with_suffix(destination.suffix + ".part")
    url = REPO + relative
    print(f"Downloading {relative} ...", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "VideoEditing-NaraSAR/1.0"})
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
        while block := response.read(1024 * 1024):
            output.write(block)
            digest.update(block)
            total += len(block)
            print(f"\r  {total / (1024 * 1024):,.1f} MiB", end="", flush=True)
    print()
    actual = digest.hexdigest()
    if expected and actual != expected:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch for {relative}: expected {expected}, got {actual}")
    temporary.replace(destination)
    print(f"Verified {relative} ({total:,} bytes)")


def main() -> int:
    print("Nara SAR INT8 package is about 430 MiB. The larger FP32 ONNX and model.safetensors files are not downloaded; they duplicate the chosen inference backend.")
    try:
        for relative, expected in FILES.items():
            download(relative, expected)
    except Exception as error:
        print(f"Download failed: {error}", file=sys.stderr)
        return 1
    print(f"\nModel ready: {MODEL_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
