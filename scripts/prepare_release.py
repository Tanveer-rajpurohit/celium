"""Package one signed Gradle APK and create the manifest consumed by Celium."""

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


def prepare_release(metadata_path: Path, output_dir: Path, tag: str, application_id: str) -> dict:
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0].get("filters"):
        raise ValueError("A release must contain exactly one universal APK")
    artifact = elements[0]
    if metadata["applicationId"] != application_id:
        raise ValueError("The APK application ID does not match the distribution package")
    version_name = artifact["versionName"]
    version_code = artifact["versionCode"]
    if not re.fullmatch(r"\d+\.\d+\.\d+-celium", version_name):
        raise ValueError("Expected a stable Celium version such as 7.0.5-celium")
    if tag != "v" + version_name.removesuffix("-celium"):
        raise ValueError("The Git tag must match the APK version name")
    if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code <= 0:
        raise ValueError("The APK must have a positive version code")
    source = (metadata_path.parent / artifact["outputFile"]).resolve()
    if source.parent != metadata_path.parent.resolve() or source.suffix != ".apk":
        raise ValueError("Invalid APK output path")
    apk_name = f"celium-{version_name}.apk"
    with source.open("rb") as apk_stream:
        sha256 = hashlib.file_digest(apk_stream, "sha256").hexdigest()
    manifest = {
        "schemaVersion": 1,
        "applicationId": application_id,
        "versionCode": version_code,
        "versionName": version_name,
        "apkName": apk_name,
        "sha256": sha256,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output_dir / apk_name)
    (output_dir / "celium-update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output_dir / "SHA256SUMS.txt").write_text(f"{manifest['sha256']}  {apk_name}\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--application-id", required=True)
    args = parser.parse_args()
    prepare_release(args.metadata, args.output, args.tag, args.application_id)
