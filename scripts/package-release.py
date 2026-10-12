"""Export a reviewable working-tree source snapshot; no Git commit or publication is performed."""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = {".github", "rcaproof-api", "rcaproof-core", "demo-system", "scenarios", "organization", "infra", "dashboards", "docs", "scripts", "build-evidence"}
FILES = {"pom.xml", "Dockerfile", "docker-compose.yml", "docker-compose.certified.yml", "README.md", "LICENSE", ".dockerignore", ".gitattributes", ".gitignore"}
EXCLUDED = {"target", "__pycache__", ".git", ".idea", "node_modules", ".terraform"}


def main():
    destination = ROOT / "artifacts" / "releases" / str(uuid4())
    source = destination / "source"
    source.mkdir(parents=True)
    manifest = {"scope": "Working-tree source snapshot; uncommitted and not a certified release", "files": {}}
    for top in sorted(DIRECTORIES | FILES):
        entry = ROOT / top
        if entry.is_symlink():
            raise SystemExit(f"Review symlink before packaging: {top}")
        if not entry.exists():
            raise SystemExit(f"Required release source is missing: {top}")
        paths = sorted(entry.rglob("*")) if entry.is_dir() else [entry]
        for path in paths:
            relative = path.relative_to(ROOT)
            if any(part in EXCLUDED for part in relative.parts):
                continue
            if path.is_symlink():
                raise SystemExit(f"Review symlink before packaging: {relative}")
            if not path.is_file() or path.suffix in {".log", ".pyc", ".pem", ".key"} or path.name.startswith(".env"):
                continue
            if relative.as_posix() in {"build-evidence/source.json", "build-evidence/candidate.patch"}:
                continue
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            # Source scripts are portable even when packaged from a Windows checkout.
            if target.suffix == ".sh":
                target.write_bytes(target.read_bytes().replace(b"\r\n", b"\n"))
                target.chmod(0o755)
            manifest["files"][relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    (source / "release-manifest.json").write_text(json.dumps(manifest, indent=2))
    archive = destination / "rcaproof-source.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        bundle.add(source, arcname="rcaproof")
    result = dict(source=str(source), archive=str(archive), sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), files=len(manifest["files"]))
    (destination / "package.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
