#!/usr/bin/env python3
"""Run the downloaded Nara SAR local speech recognizer on an audio file."""
from __future__ import annotations
import argparse
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
MODEL_DIR = HERE / "model"
RUNNER = MODEL_DIR / "transcribe.py"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path, help="Input audio file (WAV, FLAC, MP3, M4A, etc.)")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if not RUNNER.is_file():
        parser.error("Model files are not downloaded yet. Run: python3 tools/nara_sar/download_model.py")
    if not args.audio.is_file():
        parser.error(f"Audio file does not exist: {args.audio}")
    command = [sys.executable, str(RUNNER), "--audio", str(args.audio.resolve()), "--model-dir", str(MODEL_DIR), "--threads", str(args.threads)]
    if args.json:
        command.append("--json")
    return subprocess.run(command, cwd=MODEL_DIR).returncode


if __name__ == "__main__":
    raise SystemExit(main())
