# Scene Understanding & Navigation Plan

How the VLM pilot gets from "looks at a frame and guesses" to "understands the
scene and navigates around things".

## Framing: separate semantics from geometry

Navigation needs two different questions answered, with very different
requirements:

| Question | Layer | Speed | Right tool |
|---|---|---|---|
| What am I looking at, what should I do about it? | Semantics | Slow is fine (~0.2 Hz) | VLM |
| Where can I physically drive right now? | Geometry | Fast + reliable (~4 Hz) | Segmentation / depth model |

The pilot currently asks the VLM to do both implicitly in one shot. VLMs are
the wrong tool for geometry: they can't measure distance, they hallucinate
spatial relations, and on-device every output token costs decode time, so
pixel-accurate masks emitted as tokens are never viable there. The Gemma
family is also specifically weak at spatial grounding
([Roboflow's testing](https://blog.roboflow.com/gemma-3/) found Gemma failed
zero-shot detection while Qwen-VL passed). The classic robotics answer is a
fast free-space layer for geometry with a planner on top
([example](https://www.mdpi.com/2227-7390/8/5/855)).

## Level 0 — Structured scene report in the pilot prompt

**Status: done**
**Cost: hours. No new tech.**

Change the pilot JSON so the model must describe before it decides:

```json
{
  "scene": {"left": "clear floor", "center": "chair, close", "right": "wall"},
  "action": "veer_left",
  "speed": "slow",
  "reason": "chair ahead, open floor to the left"
}
```

Three vertical corridors is "poor man's segmentation" at exactly the
resolution the action vocabulary needs — the car can only go left, center, or
right anyway. Two wins:

- Describe-then-decide is chain-of-thought in miniature; forcing the scene
  tokens to be generated *before* the action tokens measurably improves the
  action choice.
- A loggable, displayable record of what the model *thought* it saw — the
  primary debugging tool for every bad decision from now on.

Touch points: `DriveCommandParsing` (prompt + DTO), `DriveCommand` (add
`SceneReport`), `RCViewModel.executePilotCommand` (surface scene in output).

## Level 1 — Fuse the detection pass into the pilot

**Status: not started — optional, most valuable in cloud mode.**
**Cost: about a day.**

Run `detectObjects` first, convert boxes to egocentric facts, inject them as
text into the pilot prompt:

- Box center-x → left / center / right.
- Box bottom-edge y → proximity proxy (forward-facing camera on the floor:
  lower bottom edge = closer).

Caveat: doubles per-decision latency on-device (two inference calls). Cloud
detection quality is also far better, so this level mostly pays off there.

## Level 2 — Dedicated geometry model as a reflex layer

**Status: implemented — needs bench tuning.** Model: MiDaS v2.1 small (official
TFLite artifact, ~63 MB). Two ways to install it: the in-app Settings →
"Obstacle depth" download button (no adb, no account — fetched from the public
GitHub release), or `scripts/download-depth-model.sh` (downloads + adb-pushes).
Runtime: LiteRT interpreter (`com.google.ai.edge.litert:litert`). Availability
notes: Depth Anything V2 only ships as per-device Qualcomm AI Hub exports;
first-party MediaPipe segmentation models have no floor class — hence depth.
Tune on the bench: `CorridorReport.BLOCK_THRESHOLD` and the p90/lower-half
scoring in `DepthEstimator`.
**Cost: a weekend.**

Run a small on-device model alongside the VLM at the 4 fps frame-sampler rate:

- Floor segmentation (MediaPipe ImageSegmenter, DeepLabV3-class model), or
- Monocular depth (Depth Anything small class, via LiteRT).

Compute a per-corridor drivability score from its output, then use it three ways:

1. Inject into the pilot prompt as ground truth
   ("center corridor 80% floor, left 20%").
2. **Reflex veto** in `RCViewModel.dispatchDriveCommand`: refuse FORWARD when
   center free-space drops below a threshold, regardless of what the VLM said.
3. **Reflex drive** (`ReflexPilot.decide`): a standalone reactive navigator that
   turns each corridor reading directly into a drive command (forward / turn
   toward the open side / back out when boxed in) with no VLM in the loop. It's
   the fastest closed loop (runs at the depth frame rate) and doubles as the
   cheapest way to watch perception -> action against the mock receiver. Toggled
   from the Drive Pad card.

Two-layer architecture: VLM does mission-level reasoning at ~0.2 Hz, the
reflex layer does don't-hit-things at ~4 Hz. Extends the existing pattern of
the app not trusting the model (arm gating, auto-stop watchdog).

Known limitation from the literature: floor segmentation misses thin
obstacles (chair legs).

## Level 3 — Better-grounded VLM backend

**Status: not started — opportunistic.**

- Cloud: Qwen3-VL grounding is strong; Gemini's API can return actual
  segmentation masks. Both work today through the existing `VlmClient`.
- On-device: no LiteRT-packaged VLM with real grounding heads exists yet. If
  one ships, the `VisionAnalyzer` abstraction means it slots in cleanly.

## Adjacent gap — pilot statelessness

Each pilot decision sees one frame with no memory, which produces cars that
oscillate left-right forever or re-approach the same corner. Appending the
last few commands + reasons to the pilot prompt is nearly free and helps a lot
once continuous pilot mode is doing real navigation. Can ride along with any
level.

## Recommended order

1. Level 0 now (nearly free; improves decisions and debuggability).
2. Level 2 next (takes the job the VLM is worst at away from it entirely).
3. Statelessness fix alongside either.
4. Level 1 only if running primarily on the cloud backend.
5. Level 3 as the ecosystem allows.
