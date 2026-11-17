# Apex Drive

Your own storage for your phone, on your own Cloudflare account. It takes media off the
phone, keeps a verified copy in the cloud, and only then clears space — after a waiting
period, never straight away.

Built for Android. This is not a website that pretends to reach your gallery: the phone
side is a program that runs on the phone and talks to your worker.

## The one rule that is not negotiable

> Upload → verify → wait 7–30 days → then remove.

- **Verify** means size and checksum match what was uploaded. Not "the request returned 200".
- **Wait** means the phone copy stays put for a configurable window after a verified upload.
- **Then remove** means removal is the very last step, and it is a batch you approve.

If any part of that chain is uncertain, nothing is deleted. A full cloud bucket or a failed
upload costs you nothing; a wrong deletion costs you a memory.

## Files it doesn't take are left alone

Anything Apex Drive decides not to upload — because it is unclear, unreadable, too large,
already there, or the rules skip it — is simply **not touched**. Nothing is removed because
it was ignored. Ignored is not deleted.

## What actually frees space

Not an AI guessing whether a photo is "worth it". Boring rules do most of the work:

| Target | Why it is big |
| --- | --- |
| Byte-identical duplicates | Bursts, re-saves, forwarded files |
| Screenshots older than 90 days | Endless, rarely reopened |
| WhatsApp / Telegram media older than 60 days | Usually the single biggest folder on a phone |
| Anything already uploaded and verified | Genuinely redundant |

AI tagging and search can come later. It never gets to decide what is deleted.

## Phases

**Phase 1 — space report.**
Read-only scan. Tells you how much is recoverable and where, so you can decide whether the
rest of the project is worth building. Uploads nothing. Deletes nothing.

**Phase 2 — upload and verify.**
Worker endpoint that streams files into Cloudflare R2 and records a checksum manifest,
plus the Android app that sends them. The phone keeps its copy. Nothing is removed here.

**Phase 3 — the window and the batch.**
After the waiting period, verified-and-old-enough items appear as one batch for approval.
Approve, and only then does the phone remove them.

**Phase 4 — automation.**
Runs while charging and on Wi-Fi, survives reboots, reports what it did. Android background
limits are the real work here, not the upload code.

## Getting the script onto the phone

The phase 1 script is not on your phone yet. Termux starts in its own home folder, and this
repo is private, so there is nothing to find there until you put it there.

Easiest — no token needed, because the browser is already signed in:

1. On the phone, open the repo's GitHub page and tap `space_report.py`.
2. Tap the download icon in the file header (or **⋯ → Download** in the GitHub app).
3. In Termux:

```bash
cd ~
cp ~/storage/downloads/space_report.py .
python3 space_report.py
```

## Running phase 1 on your phone

```bash
# in Termux, in the folder that contains space_report.py
termux-setup-storage          # grants access to /sdcard (answer y)
python3 space_report.py
```

You must be in the folder that contains `space_report.py`, otherwise you get:
`can't open file '.../space_report.py': [Errno 2] No such file or directory`

Useful flags:

```bash
python3 space_report.py --root /sdcard/DCIM --root /sdcard/Download
python3 space_report.py --json report.json
python3 space_report.py --screenshot-days 60 --messenger-days 30
```

Sample output:

```
Ways to free space
  duplicates           976.6 KB   1 groups of identical files
  screenshots          195.3 KB   1 older than 90 days
  whatsapp/telegram      2.9 MB   1 older than 60 days
```

Notes:

- Folders owned by other apps (`Android/media/com.whatsapp/...`) are often **not readable**
  on Android 11+. The report says so instead of guessing, and you can move WhatsApp media
  to a readable folder if you want it counted.
- Files touched in the last 24 hours are never counted as removable.
- Big libraries take a while: only files whose sizes collide get hashed.

## Non-goals

- No silent deletion, ever.
- No iOS version (an app cannot delete photos there without confirmation).
- No giving a remote shell (Telegram bot, relay agent) permission to delete files.
- No re-encoding or "optimising" your originals. What you shot is what is stored.

## Status

What exists today, what is stubbed and where the worker routes live: see [STATUS.md](STATUS.md).
The phone app has its own notes in [android/README.md](android/README.md).
