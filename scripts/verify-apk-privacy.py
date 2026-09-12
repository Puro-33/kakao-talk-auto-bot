"""Reject APKs that package the public CI fixture token (never use real tokens)."""
import sys
import zipfile
from pathlib import Path

SENTINEL = b"hf_CI_ONLY_DO_NOT_EMBED_9f3427e1"


def verify(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            contents = archive.read(entry)
            for encoding in (SENTINEL, SENTINEL.decode().encode("utf-16le")):
                if encoding in contents:
                    raise SystemExit(f"FAIL: CI fixture token packaged in {apk.name}:{entry.filename}")
    print(f"PASS: no CI fixture token in {apk.name}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify-apk-privacy.py APK [APK ...]")
    for argument in sys.argv[1:]:
        verify(Path(argument))
