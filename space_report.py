#!/usr/bin/env python3
"""Apex Drive — phase 1: the space report.

This script is READ ONLY. It does not upload, move, or delete anything.
It tells you how much space you could free, and where it is.

Run it on the phone (Termux) or on a copy of the phone's folders:

    python3 space_report.py
    python3 space_report.py --root /sdcard/DCIM --root /sdcard/Download
    python3 space_report.py --json report.json

Nothing is written except the optional JSON file you ask for.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from collections import defaultdict

DEFAULT_ROOTS = [
    "/sdcard/DCIM",
    "/sdcard/Pictures",
    "/sdcard/Movies",
    "/sdcard/Download",
    "/sdcard/Documents",
    "/sdcard/WhatsApp/Media",
    "/sdcard/Telegram",
    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media",
]

IMG = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".dng", ".raw", ".tif", ".tiff"}
VID = {".mp4", ".mov", ".3gp", ".mkv", ".avi", ".webm", ".m4v", ".ts", ".flv", ".wmv"}
AUD = {".mp3", ".m4a", ".opus", ".ogg", ".wav", ".aac", ".flac", ".amr"}
DOC = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv", ".rtf", ".epub"}

QUICK_CHUNK = 64 * 1024
RECENT_SKIP_SECONDS = 24 * 60 * 60  # never count files touched in the last day


def category_of(path: str) -> str:
    ext = os.path.splitext(path)[1].lower()
    if ext in IMG:
        return "images"
    if ext in VID:
        return "videos"
    if ext in AUD:
        return "audio"
    if ext in DOC:
        return "documents"
    if ext == ".apk":
        return "apk"
    return "other"


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024.0 or unit == "TB":
            return f"{n:,.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0
    return f"{n:.1f} TB"


def looks_like_screenshot(path: str) -> bool:
    name = os.path.basename(path).lower()
    low = path.lower()
    return name.startswith("screenshot") or "/screenshots/" in low or "/screen recordings/" in low


def looks_like_messenger_media(path: str) -> bool:
    low = path.lower()
    return "whatsapp" in low or "telegram" in low or "/android/media/" in low


def quick_key(path: str, size: int) -> str | None:
    """Cheap fingerprint: size plus the first and last chunk. Used to find candidates only."""
    try:
        h = hashlib.blake2b(digest_size=16)
        h.update(str(size).encode())
        with open(path, "rb") as f:
            h.update(f.read(QUICK_CHUNK))
            if size > QUICK_CHUNK:
                f.seek(max(0, size - QUICK_CHUNK))
                h.update(f.read(QUICK_CHUNK))
        return h.hexdigest()
    except OSError:
        return None


def full_hash(path: str) -> str | None:
    h = hashlib.blake2b(digest_size=16)
    try:
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1024 * 1024), b""):
                h.update(block)
        return h.hexdigest()
    except OSError:
        return None


def scan(roots: list[str]) -> tuple[list[dict], list[str]]:
    files: list[dict] = []
    skipped: list[str] = []
    seen_paths: set[str] = set()

    for root in roots:
        if not os.path.isdir(root):
            skipped.append(f"{root} (does not exist)")
            continue
        for dirpath, dirnames, filenames in os.walk(root, followlinks=False, onerror=lambda e: skipped.append(str(e))):
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]
            for name in filenames:
                if name.startswith("."):
                    continue
                path = os.path.join(dirpath, name)
                if path in seen_paths:
                    continue
                try:
                    st = os.stat(path, follow_symlinks=False)
                except OSError:
                    continue
                if not os.path.isfile(path):
                    continue
                seen_paths.add(path)
                files.append(
                    {
                        "path": path,
                        "size": st.st_size,
                        "mtime": st.st_mtime,
                        "cat": category_of(path),
                    }
                )
    return files, skipped


def find_duplicates(files: list[dict]) -> list[list[dict]]:
    """Groups byte-identical files. Hashing only happens where sizes collide."""
    by_size: dict[int, list[dict]] = defaultdict(list)
    for f in files:
        if f["size"] > 0:
            by_size[f["size"]].append(f)

    groups: list[list[dict]] = []
    for size, candidates in by_size.items():
        if len(candidates) < 2:
            continue
        by_quick: dict[str, list[dict]] = defaultdict(list)
        for f in candidates:
            key = quick_key(f["path"], size)
            if key:
                by_quick[key].append(f)
        for group in by_quick.values():
            if len(group) < 2:
                continue
            by_full: dict[str, list[dict]] = defaultdict(list)
            for f in group:
                digest = full_hash(f["path"])
                if digest:
                    by_full[digest].append(f)
            for same in by_full.values():
                if len(same) > 1:
                    groups.append(sorted(same, key=lambda x: x["mtime"]))
    return groups


def build_report(files: list[dict], skipped: list[str], screenshot_days: int, messenger_days: int) -> dict:
    now = time.time()
    totals: dict[str, dict] = defaultdict(lambda: {"count": 0, "bytes": 0})
    for f in files:
        totals[f["cat"]]["count"] += 1
        totals[f["cat"]]["bytes"] += f["size"]

    duplicate_groups = find_duplicates(files)
    duplicate_extra = 0
    duplicate_paths: list[str] = []
    for group in duplicate_groups:
        keep = group[0]
        for extra in group[1:]:
            duplicate_extra += extra["size"]
            duplicate_paths.append(extra["path"])
        _ = keep

    old_screenshots = [
        f for f in files
        if looks_like_screenshot(f["path"])
        and now - f["mtime"] > screenshot_days * 86400
        and now - f["mtime"] > RECENT_SKIP_SECONDS
    ]
    old_messenger = [
        f for f in files
        if looks_like_messenger_media(f["path"])
        and now - f["mtime"] > messenger_days * 86400
        and now - f["mtime"] > RECENT_SKIP_SECONDS
    ]

    def total(items: list[dict]) -> int:
        return sum(i["size"] for i in items)

    largest = sorted(files, key=lambda f: f["size"], reverse=True)[:10]

    return {
        "readOnly": True,
        "scannedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "fileCount": len(files),
        "totalBytes": sum(f["size"] for f in files),
        "byCategory": {k: dict(v) for k, v in sorted(totals.items())},
        "duplicates": {
            "groups": len(duplicate_groups),
            "reclaimBytes": duplicate_extra,
            "examples": duplicate_paths[:20],
        },
        "screenshots": {
            "olderThanDays": screenshot_days,
            "count": len(old_screenshots),
            "reclaimBytes": total(old_screenshots),
        },
        "messengerMedia": {
            "olderThanDays": messenger_days,
            "count": len(old_messenger),
            "reclaimBytes": total(old_messenger),
        },
        "largestFiles": [
            {"path": f["path"], "size": f["size"], "sizeHuman": human(f["size"])} for f in largest
        ],
        "skipped": skipped,
    }


def print_report(report: dict) -> None:
    print()
    print("Apex Drive — space report")
    print("Read only. Nothing was uploaded, moved or deleted.")
    print("=" * 60)
    print(f"Files found : {report['fileCount']:,}")
    print(f"Total size  : {human(report['totalBytes'])}")
    print()
    print("By type")
    for cat, data in report["byCategory"].items():
        print(f"  {cat:<10} {human(data['bytes']):>12}   {data['count']:,} files")
    print()
    print("Ways to free space")
    dup = report["duplicates"]
    print(f"  duplicates       {human(dup['reclaimBytes']):>12}   {dup['groups']:,} groups of identical files")
    scr = report["screenshots"]
    print(f"  screenshots      {human(scr['reclaimBytes']):>12}   {scr['count']:,} older than {scr['olderThanDays']} days")
    msg = report["messengerMedia"]
    print(f"  whatsapp/telegram{human(msg['reclaimBytes']):>12}   {msg['count']:,} older than {msg['olderThanDays']} days")
    print()
    potential = dup["reclaimBytes"] + scr["reclaimBytes"] + msg["reclaimBytes"]
    print(f"  estimated total  {human(potential):>12}   (these lists can overlap)")
    print()
    if report["largestFiles"]:
        print("Largest files")
        for f in report["largestFiles"]:
            print(f"  {f['sizeHuman']:>10}  {f['path']}")
        print()
    if report["skipped"]:
        print("Not readable (normal on newer Android — other apps' private folders)")
        for s in report["skipped"][:10]:
            print(f"  {s}")
        if len(report["skipped"]) > 10:
            print(f"  ... and {len(report['skipped']) - 10} more")
        print()
    print("Next step is upload, not deletion. Nothing here is removed automatically.")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Read-only report of reclaimable phone storage.")
    ap.add_argument("--root", action="append", default=None, help="Folder to scan (repeatable)")
    ap.add_argument("--json", default=None, help="Also write the report as JSON here")
    ap.add_argument("--screenshot-days", type=int, default=90)
    ap.add_argument("--messenger-days", type=int, default=60)
    args = ap.parse_args(argv)

    roots = args.root if args.root else DEFAULT_ROOTS
    files, skipped = scan(roots)
    if not files:
        print("No readable files found. On Android, grant Termux storage access:", file=sys.stderr)
        print("  termux-setup-storage", file=sys.stderr)
        for s in skipped:
            print(f"  skipped: {s}", file=sys.stderr)
        return 1

    report = build_report(files, skipped, args.screenshot_days, args.messenger_days)
    print_report(report)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"JSON written to {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
