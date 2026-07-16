# On-Device (Offline) VLM for Strix

Run the vision-language model entirely on the phone — no network, no API key.
Uses MediaPipe's LLM Inference API (LiteRT) with a Gemma 3n multimodal `.task`
model. This documents the full setup plus every failure mode we've actually hit.

## Requirements

- A **physical phone**, roughly Pixel 8 / Galaxy S23 class or newer.
  **Emulators do not work**: the vision pipeline hard-requires an OpenCL GPU
  delegate, and emulators have no vendor OpenCL driver (see Troubleshooting).
- ~3–5 GB free storage for the model, and enough RAM to hold it during inference.
- A Hugging Face account (the Gemma models are license-gated).

## Models

| Model | File | Size | Notes |
|---|---|---|---|
| Gemma 3n E2B (INT4) | `gemma-3n-E2B-it-int4.task` | ~3.0 GB | Smaller, ~2x faster — **recommended** |
| Gemma 3n E4B (INT4) | `gemma-3n-E4B-it-int4.task` | ~4.2 GB | Higher quality, slower |

The app scans for `.task`/`.litertlm` files in its own storage
(`/sdcard/Android/data/com.resourcefork.rccontrol/files/models/`). If several are
present, **the most recently modified one wins** — download/push a new model and it
takes effect on the next Re-scan without deleting the old one (delete the old file
whenever you want the space back).

## Getting the model onto the phone

### Path A — in-app download (no computer needed)

1. In the Camera/VLM section, keep "Run on-device (offline)" toggled on. With no
   model present, the download panel appears.
2. Tap **Accept license ↗** — opens the model's Hugging Face page in an in-app tab.
   Accept Google's Gemma license (one time per account; approval is immediate).
3. Tap **Create a token ↗** — opens HF's fine-grained token page. **Critical:**
   check ☑ *"Read access to contents of all public gated repos you can access"*.
   Account access alone is NOT enough — a fine-grained token without this checkbox
   gets 403 even after you've accepted the license. (A classic "Read" token also
   works and needs no checkbox.)
4. Paste the token into the field (it's remembered across runs; whitespace from
   sloppy pastes is stripped automatically), pick a model, tap **Download**.
   A progress bar tracks the multi-GB pull.
5. On success the app auto-detects the model and warms it up.

> **Corporate/VPN network warning:** Hugging Face's download CDN
> (`cas-bridge.xethub.hf.co`) blocks some corporate egress IP ranges. The telltale
> is a 403 whose body is CloudFront/S3 XML (`<Error><Code>AccessDenied</Code>...`)
> — your token and license are fine; the network is the problem. Use a phone
> hotspot / home Wi-Fi, or use Path B (the desktop `hf` CLI uses a different
> protocol that isn't blocked).

### Path B — desktop script + USB push

1. On the Mac: `./scripts/download-vlm-model.sh` — pick a model by number. The
   script walks you through HF token/license in the browser and downloads to
   `.model-cache/`.
2. Install the app on the phone first (`./gradlew installDebug`), connect USB
   (USB debugging on), then choose **`p`** in the script menu to `adb push` the
   active model into the app's storage.
3. In the app, tap **Re-scan** (or relaunch).

## What the app does from there

- **Status line** under the toggle: "Model found · <file>" → "Loading model into
  memory…" (with a spinner and a progress banner under the app bar) → "Model ready".
- **Warm-up is automatic and in the background** — on app start, when toggling
  on-device mode on, and after Re-scan — so the first Analyze/Detect doesn't stall
  on a multi-GB model load. First load takes a while; it's held in memory after.
- **Analyze Frame** streams a text description; **Detect Objects** returns
  bounding boxes drawn over the camera preview. On-device inference returns in one
  shot (boxes appear together when generation finishes).

## Performance notes

- The app requests the **GPU backend** for the LLM decoder automatically and falls
  back to default if GPU init fails — check `adb logcat -s LocalVlmClient`; a
  "GPU backend unavailable" warning means you're on the slow path.
- **E2B vs E4B** is the biggest lever: E2B is roughly twice as fast.
- Output length is decode time: the detection prompt caps at 6 objects with short
  labels, and shorter free-text prompts finish sooner. Edit the Prompt field with
  brevity in mind.
- Image prefill (a few seconds per frame) is a fixed cost even on GPU.
- If on-device speed is still not enough, see `self-hosted-vlm-server.md` —
  a desktop GPU is an order of magnitude faster and the app switches with a toggle.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| 403, body is CloudFront/S3 XML `AccessDenied` | Network blocked by HF's CDN (corporate/VPN egress). Different network, or Path B. |
| 403, HF-style message about gated repo | License not accepted, or fine-grained token missing the gated-repos checkbox. |
| 401 | Token invalid — re-paste it. Whitespace is auto-stripped since v2. |
| `Failed to open OpenCL library ... libvndksupport.so` | Emulator (no OpenCL — use a real phone). On a real phone this would mean the `uses-native-library` manifest entries are missing — they're present in this repo. |
| App crashes at launch: SIGSEGV in `libllm_inference_engine_jni.so` | Old build on an emulator with a model file present: warm-up requested the GPU backend, which segfaults natively without a GPU driver. Fixed — the app now skips GPU + warm-up on emulators. Optionally free the space: `adb shell rm -r /sdcard/Android/data/com.resourcefork.rccontrol/files/models` |
| "No model on device" after a push | Tap **Re-scan**. Verify the file: `adb shell ls -la /sdcard/Android/data/com.resourcefork.rccontrol/files/models/` |
| Very slow inference | Confirm GPU backend took (logcat), switch to E2B, shorten prompts. |
| Full error details | `adb logcat -s RCViewModel ModelDownloader LocalVlmClient` |
