#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PRODUCTION_PAYLOAD_REPOSITORY = "igorcv88/Root-My-Galaxy-Payloads-Extended"
PRODUCTION_PREFIX = (
    f"https://raw.githubusercontent.com/{PRODUCTION_PAYLOAD_REPOSITORY}/main/"
)
ASSET_ROOT = ROOT / "app/src/main/assets/production-payloads"
JNI_HELPER = ROOT / "app/src/main/jniLibs/arm64-v8a/libcve43499root.so"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def artifact_path(payload_root: pathlib.Path, artifact: dict, label: str) -> pathlib.Path:
    url = str(artifact.get("url", ""))
    if not url.startswith(PRODUCTION_PREFIX):
        raise SystemExit(f"{label} is not isolated to the production payload repository: {url}")
    relative = url.removeprefix(PRODUCTION_PREFIX)
    if not relative or relative.startswith("/") or ".." in pathlib.PurePosixPath(relative).parts:
        raise SystemExit(f"Unsafe {label} path: {relative}")
    source = payload_root / relative
    if not source.is_file():
        raise SystemExit(f"Missing {label}: {relative}")
    expected_size = int(artifact.get("size", -1))
    expected_sha = str(artifact.get("sha256", "")).lower()
    actual_size = source.stat().st_size
    actual_sha = sha256(source)
    if actual_size != expected_size:
        raise SystemExit(
            f"{label} size mismatch: expected={expected_size} actual={actual_size}"
        )
    if actual_sha != expected_sha:
        raise SystemExit(
            f"{label} SHA-256 mismatch: expected={expected_sha} actual={actual_sha}"
        )
    destination = ASSET_ROOT / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return source


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(
            "usage: prepare_production_payload_bundle.py <production payload checkout>"
        )

    payload_root = pathlib.Path(sys.argv[1]).resolve()
    manifest_path = payload_root / "support/targets-v3.json"
    if not manifest_path.is_file():
        raise SystemExit(f"Missing production v3 manifest: {manifest_path}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 3:
        raise SystemExit("Production app requires payload schema v3")
    payloads = manifest.get("payloads")
    if not isinstance(payloads, list) or not payloads:
        raise SystemExit("Production v3 manifest has no payloads")

    if ASSET_ROOT.exists():
        shutil.rmtree(ASSET_ROOT)
    ASSET_ROOT.mkdir(parents=True)

    helper_contracts: set[tuple[int, str]] = set()
    canonical_helper_source: pathlib.Path | None = None

    for target in payloads:
        payload_id = str(target.get("payloadId", "unknown"))
        for key in ("exploit", "kernelsu", "rootHelper"):
            artifact = target.get(key)
            if artifact is None:
                continue
            if not isinstance(artifact, dict):
                raise SystemExit(f"Invalid {payload_id} {key} metadata")
            source = artifact_path(payload_root, artifact, f"{payload_id} {key}")
            if key == "rootHelper":
                contract = (int(artifact["size"]), str(artifact["sha256"]).lower())
                helper_contracts.add(contract)
                if canonical_helper_source is None:
                    canonical_helper_source = source

    if len(helper_contracts) != 1 or canonical_helper_source is None:
        raise SystemExit(
            "Production profiles must share one bundled root-helper contract: "
            f"{helper_contracts}"
        )

    helper_size, helper_sha = next(iter(helper_contracts))
    ASSET_ROOT.joinpath("targets-v3.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    JNI_HELPER.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(canonical_helper_source, JNI_HELPER)
    if JNI_HELPER.stat().st_size != helper_size or sha256(JNI_HELPER) != helper_sha:
        raise SystemExit("Bundled JNI root helper verification failed")

    payload_commit = subprocess.check_output(
        ["git", "-C", str(payload_root), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    provenance = (
        f"repository={PRODUCTION_PAYLOAD_REPOSITORY}\n"
        f"payload_commit={payload_commit}\n"
        f"helper_size={helper_size}\n"
        f"helper_sha256={helper_sha}\n"
    )
    ASSET_ROOT.joinpath("provenance.txt").write_text(provenance, encoding="utf-8")

    print(provenance, end="")
    print(f"payload_count={len(payloads)}")


if __name__ == "__main__":
    main()
