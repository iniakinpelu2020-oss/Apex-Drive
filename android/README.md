# Apex Drive — Android app

The phone half. Reads the gallery, uploads new media to the worker, asks the worker to
verify each copy, and stops.

**There is no deletion code in this module.** That is not an oversight. Phase 3 adds it
behind an explicit approval batch; until then the app cannot remove anything from the phone.

## What works in this scaffold

- MediaStore scan of photos and videos, with counts and total size on screen
- SHA-256 of each file, streamed from the content URI (nothing is loaded into memory)
- Streamed `PUT /api/drive/upload` to your Automation worker
- `POST /api/drive/verify` straight after each upload, so "uploaded" and "verified" are
  different states in the UI and in the manifest
- WorkManager job that only runs on Wi-Fi while charging
- Remembers what it already sent, so a second run does not re-upload

## What is stubbed (deliberately)

| Stub | Why | What replaces it |
| --- | --- | --- |
| `DriveConfig.token` is a hand-pasted Firebase ID token | Real sign-in needs Firebase Auth, `google-services.json` and a refresh loop | Firebase Auth + token refresh before each run |
| Scan caps at 3000 items per run | Keeps the first build simple and predictable | Paged scan with a saved cursor |
| No resumable uploads | A dropped connection re-sends that one file | Multipart, or an upload straight to the bucket, for big video |
| Files over ~95 MB are refused | Cloudflare caps a Worker request body at 100 MB on the free plan | Signed direct-to-bucket upload from the phone |
| No UI list of uploaded files | The screen is a status readout | Library screen reading `GET /api/drive/list` |
| Deletion | Phase 3 | `MediaStore.createDeleteRequest` + the approval batch from the worker |

## Building it

1. Open the `android/` folder in Android Studio (Hudson/Giraffe or newer).
2. Let it sync — Gradle 8.7+ and Android SDK 34 are needed.
3. Run on a device or emulator with Android 8.0+ (minSdk 26).
4. In the app, paste your worker URL (for example
   `https://automation.iniakinpelu2020.workers.dev`) and a Firebase ID token, then Save.
5. Tap **Upload now**. It waits for Wi-Fi and charging.

To get an ID token for testing, sign in to the Automation web app in a browser and copy the
token from its network tab. It expires in about an hour — that is expected at this stage.

## The order that must not change

```
scan → hash → upload → verify → (wait days) → approve → remove
```

If you are tempted to skip verify because the upload returned 200: don't. A 200 means the
request was accepted, not that the bytes are in the bucket. Verify re-reads the stored
object and compares size and a sample hash.
