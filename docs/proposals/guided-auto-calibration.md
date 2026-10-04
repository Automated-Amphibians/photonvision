# Proposal: Guided automatic camera calibration

Status: proposed feature; this document does not implement calibration changes.

## Problem and goal

Camera calibration should be possible by starting a session and moving a printed
calibration board in front of the camera. PhotonVision should automatically save
useful views and show where observations are missing or weak, without requiring
the operator to alternate between holding the board and clicking a snapshot button.

The broader goal is easier, more accurate, more responsive AprilTag localization.
This proposal addresses the calibration experience first. It does not promise a
particular pose accuracy or frame rate, and does not change the AprilTag detector.
The motivating robot setup uses two USB cameras per Orange Pi; each physical
camera and operating resolution still needs its own calibration.

## Desired workflow

1. Select the camera, resolution, board type, and measured board dimensions as today.
   Remind the operator to print without scaling and mount the page on a flat,
   rigid backing. Automatic capture cannot compensate for a warped board or
   incorrect physical dimensions.
2. Start **Guided auto-capture** and move the board around the camera's field of view.
3. Show detected corners and a live overlay of coverage from accepted snapshots.
   Clearly distinguish missing, weak, and adequately sampled regions with labels
   or patterns as well as color.
4. Automatically capture a valid, sufficiently sharp, stable view when it adds
   useful spatial, scale, or perspective diversity. Give an unmistakable visual
   confirmation and update the snapshot count and coverage immediately.
5. Give one actionable prompt at a time: **Move toward the upper-right**, **Tilt
   the board**, **Move closer**, or **Hold still**. Show why a view was not saved,
   such as **Similar view already captured** or **Not enough corners visible**.
6. Indicate **Ready to try calibration** when the dataset meets capture criteria.
   The operator explicitly finishes, reviews the solve, and applies the result.
   Failed or weak results should suggest additional views instead of discarding
   the session or replacing the previous calibration.

Keep manual capture, pause/resume, snapshot review/removal, and cancel available.
Removing a snapshot must update coverage and readiness. No per-frame clicking or
expert tuning should be necessary for the normal guided workflow.

## Frame selection

Run selection in the backend using the existing board detections, not browser
screenshots. Apply quality gates before evaluating whether a frame is useful:

- Sufficient correctly identified, well-distributed corners for the board type.
  Partial ChArUco views may be useful; preserve the detector's validity rules.
- Adequate board size and corner spacing, with acceptable sharpness and contrast.
- Brief stability based on successive corner observations to avoid motion blur.
- A capture cooldown and a session memory/snapshot limit to avoid repeated
  captures and unbounded storage. Rejected frames should not be retained.

For eligible views, compare normalized board location, image footprint/scale,
in-plane rotation, and perspective/tilt descriptors with accepted snapshots.
Accept a view if it improves an under-sampled region or adds meaningful scale or
orientation diversity. A timer alone is insufficient: a stationary board should
not generate a large set of near-duplicate images.

Before a reliable intrinsic estimate exists, use image-space descriptors such as
corner distributions and board homography. Do not require accurate camera-space
yaw/pitch from an uncalibrated camera. A provisional solve may later improve
guidance, but must be labeled provisional and run asynchronously at a bounded rate.
Selection thresholds and weighting need validation on recorded sessions; values
in this proposal are intentionally not claimed to be established defaults.

## Coverage and quality feedback

Maintain a modest sensor-space grid from **observed corners in accepted frames**,
not simply the area inside a board bounding box. Track contributions from distinct
views so repeated similar frames cannot make a region appear well constrained.
Track scale and perspective diversity separately: full image coverage obtained
only from straight-on views should still prompt for tilted views.

Initial feedback is a sampling/coverage heuristic. Label it **Observation coverage**,
not calibrated uncertainty or a predicted centimeter-level pose accuracy. Avoid
arbitrary percentage scores that imply a statistical confidence level.

After a solve, retain the existing reprojection-error and projection-uncertainty
inspection. Where supported, use those diagnostics to suggest areas needing more
observations. Low reprojection error or a clean uncertainty map alone does not
prove good calibration: systematic errors from board dimensions, lens models,
or board flatness may remain. Readiness is an invitation to solve, not a guarantee.

## Integration points in the current repository

These paths were inspected at source revision `2df8fb1c`:

| Component | Existing behavior | Proposed extension |
| --- | --- | --- |
| `photon-core/src/main/java/org/photonvision/vision/pipeline/Calibrate3dPipeline.java` | Runs board detection per frame; retains a detection/image when `takeSnapshot` is requested; broadcasts calibration state | Add session-owned capture selection and guidance state; route automatic and manual capture through shared retention logic |
| `photon-core/src/main/java/org/photonvision/vision/pipe/impl/FindBoardCornersPipe.java` | Finds board corners and associated object/image points | Supply descriptors and quality inputs without retaining every candidate image |
| `photon-core/src/main/java/org/photonvision/vision/pipeline/UICalibrationData.java` | Carries calibration session data to the UI | Add capture mode, pause state, coverage, diversity deficits, readiness, and rejection/guidance messages |
| `photon-client/src/components/cameras/CameraCalibrationCard.vue` | Controls calibration setup, snapshots, and completion | Add guided mode controls, capture feedback, and live guidance |
| `photon-client/src/components/app/photon-calibration-visualizer.vue` | Existing calibration visualization component | Evaluate reuse for the live overlay; preserve post-solve diagnostics |
| `docs/source/docs/calibration/calibration.md` | Documents capture diversity and post-solve errors/uncertainty | Document guided workflow, board preparation, and diagnostic limitations |

Reuse the existing calibration solver and saved calibration format where possible.
Coverage coordinates must match the displayed preview, including any scaling or
output rotation; corner selection should operate in the calibration image's native
coordinates. Keep guidance scoped to the active camera/session. Reset it when
camera, resolution, or board parameters change; handle disconnect/cancel cleanly.
Throttle UI messages and avoid blocking preview processing with repeated solves.

## Delivery stages

1. Automatic quality/novelty-gated capture, observation coverage overlay, basic
   diversity prompts, pause/manual fallback, and correct session cleanup.
2. Validate and tune selection on representative recordings, then add optional
   provisional solves and diagnostic-driven guidance if they materially help.

Robot-odometry-guided tag search, automatic exposure tuning, detector benchmarking,
and per-frame localization covariance are separate projects. They are not required
to deliver this calibration feature.

## Acceptance criteria and validation

- An operator can collect a useful dataset by moving the board, without clicking
  for each capture. Holding it still produces no stream of duplicate snapshots.
- Invalid, blurred, or excessively small views receive clear feedback and are
  not automatically saved. Valid novel views give timely capture confirmation.
- A dataset concentrated in one corner visibly leaves other regions missing;
  straight-on-only views still prompt for perspective diversity.
- Coverage/readiness accurately follows the accepted dataset, including removal,
  pause/resume, parameter changes, cancel, and camera disconnect.
- Automatic selection respects backend validity/minimum-snapshot requirements
  and memory limits. Manual mode remains usable.
- Cancel or a failed solve preserves the existing calibration. A successful solve
  retains normal review and explicit application behavior.
- Validate selection deterministically with recorded board sequences containing
  duplicate views, motion blur, partial boards, edge coverage, and varied tilts.
- Compare guided and manual collection on the same cameras/resolutions using
  collection time, retained-view diversity, held-out reprojection error, and
  AprilTag pose error against independent measured poses. Held-out images must
  not participate in the fit; a lower training error alone is insufficient.
- Measure preview responsiveness, CPU/memory use, and UI update rate on an Orange
  Pi with the second camera active. Set performance thresholds from that baseline
  before enabling guided capture by default.

## Open implementation decisions

- Which novelty/quality metrics give reliable behavior across wide-angle lenses,
  board sizes, lighting, ChArUco, and chessboard modes?
- How much perspective diversity can be inferred robustly before a first solve?
- What readiness rules improve held-out accuracy without overburdening operators?
- Can provisional uncertainty updates fit within the coprocessor's compute budget?
- Should guided mode become the default after validation, or remain opt-in?

Related prior discussion to review before implementation:
[PhotonVision issue #1663](https://github.com/PhotonVision/photonvision/issues/1663).
This is a reference for investigation, not a claim about its current status or
about an existing committed implementation plan.
