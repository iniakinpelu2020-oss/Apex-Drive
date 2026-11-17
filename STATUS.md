## Status

| Part | Where | State |
| --- | --- | --- |
| Space report (phase 1) | `space_report.py` | Works — read-only, deletes nothing |
| Upload + verify (phase 2, cloud) | `Automation/src/drive/` | Written and tested against a fake bucket; needs deploying |
| Phone app (phase 2, Android) | `android/` | Scaffold — see `android/README.md` for the stubs |
| Waiting window + approval (phase 3) | worker routes exist, phone side does not | Partial |
| Background automation (phase 4) | — | Not started |

The worker routes live in the separate `Automation` repo, not here, because they share its
sign-in and deployment:

- `PUT  /api/drive/upload` — streamed upload, requires `sha256` up front
- `POST /api/drive/verify` — re-reads the stored object and compares size + sample hash
- `GET  /api/drive/reclaimable?days=14` — what is past the window
- `POST /api/drive/approve?days=14` — records what the phone may remove
- `GET  /api/drive/list` — the manifest
