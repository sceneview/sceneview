#!/usr/bin/env bash
#
# fetch-assets.sh — materialise the demo apps' and website's binary 3D assets.
#
# The large .glb / .usdz / .hdr / .spz / .ply files the Android, TV and iOS demos
# embed, and the models the website serves, are NOT in git: they live as
# content-addressed files on the `assets-v1` GitHub Release of sceneview/sceneview.
# `assets/manifest.json` lists every one of them (path, sha256, size, source,
# licence). This script puts each file back at its original path, so the store
# apps and the site still embed byte-identical files.
#
#   bash tools/fetch-assets.sh                   # every scope
#   bash tools/fetch-assets.sh --scope android   # android | ios | web | tv (repeatable)
#   bash tools/fetch-assets.sh --check           # verify only; exit 1 if missing/mismatched
#   bash tools/fetch-assets.sh --register PATH…  # add/refresh manifest entries (maintainers)
#
# How it works:
#   1. Every file is downloaded once into a cache shared by all worktrees,
#      `$SCENEVIEW_ASSETS_CACHE` (default `~/.cache/sceneview-assets/<sha256>`),
#      and verified against its sha256 before it is kept.
#   2. It is then materialised at its path with an APFS clone (`cp -c`), a
#      reflink, a hardlink, or a plain copy — first that works.
#   3. A file already in place with the right hash is left alone, so the script
#      is idempotent and needs no network once the cache is warm.
#
# `SCENEVIEW_ASSETS_BASE_URL` overrides the download location (a mirror, or a
# `file://` directory in tests). The Gradle `fetchAssets` task, the iOS demo's
# "Fetch assets" build phase and CI all call this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SCENEVIEW_ASSETS_ROOT="$ROOT"
PYTHON="$(command -v python3 || true)"
if [ -z "$PYTHON" ]; then
  echo "fetch-assets: python3 is required" >&2
  exit 1
fi
# The program is passed with -c, not on stdin, so `--register -` can read paths from stdin.
read -r -d '' PROGRAM <<'PY' || true
import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(os.environ["SCENEVIEW_ASSETS_ROOT"])
MANIFEST = ROOT / "assets" / "manifest.json"
CACHE = Path(
    os.environ.get("SCENEVIEW_ASSETS_CACHE")
    or Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "sceneview-assets"
)


def log(msg: str) -> None:
    print(f"fetch-assets: {msg}", file=sys.stderr)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_manifest() -> dict:
    with open(MANIFEST) as f:
        return json.load(f)


def base_url(manifest: dict) -> str:
    return (os.environ.get("SCENEVIEW_ASSETS_BASE_URL") or manifest["baseUrl"]).rstrip("/")


def download(manifest: dict, entry: dict) -> Path:
    """Return the verified cache file for `entry`, downloading it if needed."""
    sha = entry["sha256"]
    cached = CACHE / sha
    if cached.is_file():
        return cached
    CACHE.mkdir(parents=True, exist_ok=True)
    url = f"{base_url(manifest)}/{sha}"
    fd, tmp = tempfile.mkstemp(prefix=f".{sha}.", dir=CACHE)
    os.close(fd)
    try:
        subprocess.run(
            ["curl", "-fsSL", "--retry", "4", "--retry-delay", "2", "--retry-all-errors",
             "-o", tmp, url],
            check=True,
        )
        got = sha256_of(Path(tmp))
        if got != sha:
            raise RuntimeError(f"{entry['path']}: downloaded sha256 {got} != manifest {sha} ({url})")
        os.chmod(tmp, 0o644)
        os.replace(tmp, cached)  # atomic: concurrent worktrees never see a partial file
    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"{entry['path']}: download failed ({url}): exit {e.returncode}") from None
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    log(f"downloaded {entry['path']} ({entry['size'] / 1048576:.1f} MiB)")
    return cached


def materialise(src: Path, dest: Path) -> str:
    """Place `src` at `dest`: clone, reflink, hardlink, else copy. Returns the method."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(f".{dest.name}.fetch-{os.getpid()}")
    if tmp.exists():
        tmp.unlink()
    attempts = []
    if platform.system() == "Darwin":
        attempts.append(("clone", ["cp", "-c", str(src), str(tmp)]))
    else:
        attempts.append(("reflink", ["cp", "--reflink=always", str(src), str(tmp)]))
    for method, cmd in attempts:
        if subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            os.replace(tmp, dest)
            return method
        if tmp.exists():
            tmp.unlink()
    try:
        os.link(src, tmp)
        os.replace(tmp, dest)
        return "hardlink"
    except OSError:
        if tmp.exists():
            tmp.unlink()
    shutil.copyfile(src, tmp)
    os.replace(tmp, dest)
    return "copy"


def select(manifest: dict, scopes: list[str]) -> list[dict]:
    known = set(manifest["scopes"])
    for s in scopes:
        if s not in known:
            sys.exit(f"fetch-assets: unknown scope '{s}' (known: {', '.join(sorted(known))})")
    return [e for e in manifest["files"] if not scopes or e["scope"] in scopes]


def in_place(entry: dict) -> bool:
    dest = ROOT / entry["path"]
    return (dest.is_file() and dest.stat().st_size == entry["size"]
            and sha256_of(dest) == entry["sha256"])


def orphans(manifest: dict, scopes: list[str]) -> list[str]:
    """Files in a managed folder that the manifest does not list and git does not track."""
    listed = {e["path"] for e in manifest["files"]}
    out = []
    for name, spec in manifest["scopes"].items():
        if scopes and name not in scopes:
            continue
        for d in spec["dirs"]:
            base = ROOT / d
            if not base.is_dir():
                continue
            for p in sorted(base.iterdir()):
                rel = p.relative_to(ROOT).as_posix()
                if p.is_file() and p.suffix.lower() in spec["extensions"] and rel not in listed:
                    out.append(rel)
    if not out:
        return out
    tracked = subprocess.run(["git", "-C", str(ROOT), "ls-files", "--", *out],
                             capture_output=True, text=True).stdout.split()
    return [p for p in out if p not in tracked]


def cmd_fetch(manifest: dict, scopes: list[str], jobs: int) -> int:
    entries = select(manifest, scopes)
    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        ok = list(pool.map(in_place, entries))
    todo = [e for e, good in zip(entries, ok) if not good]
    errors, methods = [], {}

    def one(entry):
        cached = CACHE / entry["sha256"]
        if cached.is_file() and sha256_of(cached) != entry["sha256"]:
            cached.unlink()  # corrupted since it was downloaded: fetch it again
        return materialise(download(manifest, entry), ROOT / entry["path"])

    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(one, e): e for e in todo}
        for fut in cf.as_completed(futures):
            try:
                m = fut.result()
                methods[m] = methods.get(m, 0) + 1
            except Exception as exc:  # report every failure, not just the first
                errors.append(str(exc))
    for err in errors:
        log(f"ERROR {err}")
    how = ", ".join(f"{n} by {m}" for m, n in sorted(methods.items())) or "nothing to do"
    label = ",".join(scopes) or "all"
    log(f"{label}: {len(entries)} files, {len(entries) - len(todo)} already in place, "
        f"{len(todo) - len(errors)} materialised ({how})")
    for p in orphans(manifest, scopes):
        log(f"warning: {p} is neither in assets/manifest.json nor tracked by git — "
            f"CI will not have it. Run: bash tools/fetch-assets.sh --register {p}")
    return 1 if errors else 0


def cmd_check(manifest: dict, scopes: list[str], jobs: int) -> int:
    entries = select(manifest, scopes)
    bad = []

    def verdict(entry):
        dest = ROOT / entry["path"]
        if not dest.is_file():
            return f"missing   {entry['path']}"
        got = sha256_of(dest)
        if got != entry["sha256"]:
            return f"mismatch  {entry['path']} (sha256 {got[:12]}…, manifest {entry['sha256'][:12]}…)"
        return None

    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        bad = [v for v in pool.map(verdict, entries) if v]
    tracked = subprocess.run(["git", "-C", str(ROOT), "ls-files", "--",
                              *[e["path"] for e in entries]],
                             capture_output=True, text=True).stdout.split()
    bad += [f"tracked   {p} (listed in the manifest but also committed — delete it from git)"
            for p in tracked]
    for b in bad:
        log(f"ERROR {b}")
    label = ",".join(scopes) or "all"
    if bad:
        log(f"--check {label}: {len(bad)} problem(s) in {len(entries)} files. "
            f"Run: bash tools/fetch-assets.sh{' --scope ' + label if scopes else ''}")
        return 1
    log(f"--check {label}: {len(entries)} files present, sha256 verified")
    return 0


_CREDITS = None


def credit_for(rel: str) -> dict:
    """Source and licence from assets/catalog.json, the file the credits are generated from."""
    global _CREDITS
    if _CREDITS is None:
        try:
            import importlib.util
            spec = importlib.util.spec_from_file_location(
                "gen_credits", ROOT / ".claude" / "scripts" / "generate-credits.py")
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            _CREDITS = (mod.NON_CATALOG_BUNDLED, mod.catalog_by_basename())
        except Exception as exc:
            log(f"warning: no credits index ({exc}); source/licence left empty")
            _CREDITS = ({}, {})
    name = Path(rel).name
    entry = _CREDITS[0].get(name) or _CREDITS[1].get(name) or {}
    return {
        "source": (entry.get("sourceUrl") or entry.get("source") or "").strip(),
        "license": (entry.get("license") or "").strip(),
        "author": (entry.get("author") or "").strip(),
    }


def cmd_register(manifest: dict, paths: list[str]) -> int:
    by_path = {e["path"]: e for e in manifest["files"]}
    uploads = []
    if paths == ["-"]:  # one path per line on stdin
        paths = [line.strip() for line in sys.stdin if line.strip()]
    for arg in paths:
        p = Path(arg).resolve()
        rel = p.relative_to(ROOT).as_posix()
        scope = next((n for n, s in manifest["scopes"].items()
                      if any(rel.startswith(d.rstrip("/") + "/") for d in s["dirs"])
                      and p.suffix.lower() in s["extensions"]), None)
        if scope is None:
            sys.exit(f"fetch-assets: {rel} is not in a managed folder (see 'scopes' in assets/manifest.json)")
        sha = sha256_of(p)
        CACHE.mkdir(parents=True, exist_ok=True)
        if not (CACHE / sha).is_file():
            materialise(p, CACHE / sha)
        entry = by_path.get(rel) or {"path": rel}
        credit = credit_for(rel)
        entry.update({"scope": scope, "sha256": sha, "size": p.stat().st_size})
        for k, v in credit.items():
            if v or k not in entry:
                entry[k] = v
        by_path[rel] = entry
        uploads.append((CACHE / sha, rel))
        log(f"registered {rel} ({sha[:12]}…)")
    manifest["files"] = sorted(by_path.values(), key=lambda e: e["path"])
    with open(MANIFEST, "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    tag = manifest["release"]["tag"]
    repo = manifest["release"]["repo"]
    log("now upload the new blobs (maintainers; existing names are skipped):")
    for src, rel in uploads:
        print(f"gh release upload {tag} -R {repo} '{src}#{Path(rel).name}'")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(prog="tools/fetch-assets.sh", description=__doc__)
    ap.add_argument("--scope", action="append", default=[],
                    help="android, ios, web or tv; repeatable or comma-separated (default: all)")
    ap.add_argument("--check", action="store_true",
                    help="verify only: exit 1 if a manifest file is missing or its sha256 differs")
    ap.add_argument("--register", nargs="+", metavar="PATH",
                    help="add or refresh manifest entries for these files")
    ap.add_argument("--jobs", type=int, default=6)
    args = ap.parse_args()
    scopes = [s for arg in args.scope for s in arg.split(",") if s]
    manifest = load_manifest()
    if args.register:
        return cmd_register(manifest, args.register)
    if args.check:
        return cmd_check(manifest, scopes, args.jobs)
    return cmd_fetch(manifest, scopes, args.jobs)


if __name__ == "__main__":
    sys.exit(main())
PY
exec "$PYTHON" -c "$PROGRAM" "$@"
