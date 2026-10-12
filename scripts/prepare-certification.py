"""Create source evidence only for a clean, immutable Git revision. No network or signing."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=root)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="Base commit of the proposed remediation change")
    args = parser.parse_args()
    output = root / "build-evidence"
    # Clear stale generated claims before validating the new checkout.
    for name in ("source.json", "candidate.patch"):
        (output / name).unlink(missing_ok=True)
    if git("status", "--porcelain", "--untracked-files=all").strip():
        raise SystemExit("Certification requires a clean committed checkout, including untracked source files")
    commit = git("rev-parse", "HEAD^{commit}").decode().strip()
    base = git("rev-parse", f"{args.base}^{{commit}}").decode().strip()
    subprocess.check_call(["git", "merge-base", "--is-ancestor", base, commit], cwd=root)
    tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    patch = git("diff", "--binary", "--full-index", "--no-ext-diff", "--no-textconv", base, commit, "--")
    if not patch:
        raise SystemExit("A nonempty reviewable patch is required")
    output.mkdir(exist_ok=True)
    (output / "candidate.patch").write_bytes(patch)
    (output / "source.json").write_text(json.dumps({"commit": commit, "baseCommit": base,
        "tree": tree, "patchSha256": hashlib.sha256(patch).hexdigest()}, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Bound source {commit} to base {base}; patch SHA-256 {hashlib.sha256(patch).hexdigest()}")


if __name__ == "__main__":
    main()
