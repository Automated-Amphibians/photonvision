# Guided calibration implementation decisions

The [shared conversation](https://chatgpt.com/share/6ac05a3d-59d0-83ea-a02c-b56d341b530b)
was reviewed alongside `guided-auto-calibration.md`. Its calibration request is
hands-free collection of useful board views, visible missing/weak observations,
and actionable movement prompts. The broader two-camera Orange Pi localization
ideas are outside this change. The proposal supersedes the discussion's tentative
camera-space yaw/pitch and confidence percentages.

## Scope and defaults

- Deliver stage 1 as an opt-in **Guided auto-capture** mode for chessboard and
  ChArUco. Manual capture remains available in either mode, including while paused.
- Reuse the detector, solver, saved coefficients, and post-solve diagnostics.
  Do not run provisional solves or tune exposure automatically.
- Keep selection in native, unrotated sensor coordinates. Draw the live grid in
  the backend preview before stream scaling, so cells and detected corners share
  the same transformation. Calibration preview uses zero image rotation.
- Own the dataset and feedback in each camera's calibration pipeline. Include
  the camera name in state messages and keep client state per camera.

## Selection heuristics

These initial constants are engineering heuristics, **not validated accuracy
claims**. Keep them together in the capture selector/metrics classes and validate
them on representative recordings before enabling guided mode by default.

- Preserve detector validity (complete chessboards; at least ten identified
  ChArUco corners). Reject degenerate or poorly distributed points. Fit a planar
  homography to corresponding object/image points for image-space orientation
  descriptors; allow partial ChArUco observations.
- Use observed corner footprint, nearest corner spacing, and bounded local corner
  patches for contrast and normalized Laplacian sharpness. Do not score the
  background or retain rejected candidate images.
- Require 300 ms of stable corresponding corners, with a maximum 500 ms gap
  between observations and an 800 ms capture cooldown. Compare against a stable
  anchor as well as the previous observation to reject slow drift.
- Reject near-duplicate location/scale/rotation/perspective descriptors even in
  weak cells. Accept novel views that contribute weak cells or add scale or
  perspective diversity; require greater novelty once those needs are satisfied.
- Use a 6 by 4 observation grid. Each distinct view contributes once to each cell
  containing observed corners. Zero views is **Missing**, one/two is **Weak**, and
  three or more is **Sampled**. Similar manual snapshots do not inflate coverage.
- Invite a guided solve after 30 distinct views, at least 18 sampled cells, two
  scale bands, and three distinct tilted descriptors. Keep the existing manual
  recommendation of 100 images, its developer bypass, and a backend minimum of
  ten observations. Readiness is an invitation to inspect a solve.
- Bound both capture modes at 250 images, 256 MiB of retained native image/point
  storage, and 700,000 corners. Explain the limit and allow removal or solving
  once ten images exist, even when readiness has not been reached.
- Broadcast ordinary feedback at most five times per second; publish captures,
  removals, mode changes, and cleanup immediately. Show capture confirmation for
  about one second. No per-frame UI percentages or uncertainty claims.

## Session and solve behavior

- Reset on session start, board/resolution changes, cancel, disconnect, or leaving
  calibration. Pause/resume resets the stability window. Removal recomputes
  coverage/diversity/readiness from the remaining dataset.
- Release every unretained detection and every removed/cleared image. Prevent
  capture/removal during a solve; defer resource cleanup if cancel/disconnect
  occurs while the solver owns the observations.
- Solve on the request thread while preview processing continues. A failed solve
  retains the dataset and old saved calibration. A successful solve is a pending
  result: review it, then explicitly apply it, or return to collecting views.
  Dataset changes invalidate a pending result. Never apply a stale result.
- Use session identifiers for snapshot and solve actions so an old browser view
  cannot remove/apply observations from a replacement session.
- Store solve-review images in an owned session subdirectory. Invalidate and clean
  unapplied images on dataset changes or cancellation; transfer ownership on Apply.
  A pending solve must not overwrite images belonging to the saved calibration.

## Validation boundary

Automated tests cover deterministic corner sequences, duplicates, motion, partial
observations, actual sharp/blurred patches, diversity, limits, removal, and cleanup.
Frontend type checking/build and backend compilation/tests verify integration.
Recorded-session threshold tuning, held-out reprojection/independent AprilTag pose
comparison, and Orange Pi performance with a second camera remain hardware
validation work. No fixture or benchmark result should be represented as having
established those claims.
