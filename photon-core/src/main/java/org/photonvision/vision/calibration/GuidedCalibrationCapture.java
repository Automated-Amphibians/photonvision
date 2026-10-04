/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Session-local, deterministic capture heuristics. Coordinates are normalized native sensor points.
 */
public final class GuidedCalibrationCapture {
    public static final int COLUMNS = 6;
    public static final int ROWS = 4;
    public static final int MIN_SNAPSHOTS = 10;
    public static final int READY_VIEWS = 30;
    public static final int MAX_SNAPSHOTS = 250;
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int MAX_CORNERS = 700_000;
    static final long STABLE_NANOS = 300_000_000L;
    static final long MAX_GAP_NANOS = 500_000_000L;
    static final long COOLDOWN_NANOS = 800_000_000L;

    public record Corner(int id, double x, double y) {}

    /** Tilt descriptors are image-space foreshortening/skew, not camera-space yaw or pitch. */
    public record View(
            double x,
            double y,
            double scale,
            double rotation,
            double foreshortening,
            double skew,
            double perspectiveX,
            double perspectiveY,
            double spacingPixels,
            List<Corner> corners) {
        public View {
            corners = List.copyOf(corners);
        }

        public boolean tilted() {
            return Math.abs(foreshortening) > 0.22
                    || Math.abs(skew) > 0.18
                    || Math.hypot(perspectiveX, perspectiveY) > 0.15;
        }

        int scaleBand() {
            return scale < 0.25 ? 0 : scale < 0.45 ? 1 : 2;
        }
    }

    public record Quality(double contrast, double sharpness) {}

    private record Snapshot(View view, long bytes, int corners) {}

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final List<View> distinctViews = new ArrayList<>();
    private final int[] coverage = new int[COLUMNS * ROWS];
    private long retainedBytes;
    private int retainedCorners;
    private View previous;
    private View anchor;
    private long previousTime;
    private long stableSince;
    private long lastCapture = Long.MIN_VALUE;

    public void reset() {
        snapshots.clear();
        retainedBytes = 0;
        retainedCorners = 0;
        lastCapture = Long.MIN_VALUE;
        resetStability();
        rebuild();
    }

    public void resetStability() {
        previous = null;
        anchor = null;
    }

    public boolean canRetain(long bytes, int corners) {
        return snapshots.size() < MAX_SNAPSHOTS
                && retainedBytes + bytes <= MAX_BYTES
                && retainedCorners + corners <= MAX_CORNERS;
    }

    /** Null means eligible. No rejected frame or native object is retained by this selector. */
    public String evaluate(View view, Quality quality, long now, long bytes, int corners) {
        if (!canRetain(bytes, corners))
            return "Snapshot storage limit reached; remove views or try calibration";
        if (view == null || view.corners().size() < 10) {
            resetStability();
            return "Not enough well-distributed corners visible";
        }
        if (view.scale() < 0.12 || view.spacingPixels() < 8) {
            resetStability();
            return "Move closer";
        }
        if (quality == null || !Double.isFinite(quality.contrast()) || quality.contrast() < 18) {
            resetStability();
            return "Improve lighting; board contrast is too low";
        }
        if (!Double.isFinite(quality.sharpness()) || quality.sharpness() < 0.08) {
            resetStability();
            return "Hold still; board is blurred or out of focus";
        }
        if (previous == null
                || now - previousTime > MAX_GAP_NANOS
                || movement(previous, view) > 0.003
                || movement(anchor, view) > 0.003) {
            anchor = view;
            stableSince = now;
        }
        previous = view;
        previousTime = now;
        if (now - stableSince < STABLE_NANOS) return "Hold still";
        if (lastCapture != Long.MIN_VALUE && now - lastCapture < COOLDOWN_NANOS)
            return "Capture cooldown; move to another view";

        double novelty =
                distinctViews.stream()
                        .mapToDouble(v -> distance(v, view))
                        .min()
                        .orElse(Double.POSITIVE_INFINITY);
        if (novelty < 1) return "Similar view already captured";
        boolean weakCell = view.corners().stream().anyMatch(c -> coverage[cell(c)] < 3);
        boolean newScale = distinctViews.stream().noneMatch(v -> v.scaleBand() == view.scaleBand());
        boolean newTilt =
                view.tilted()
                        && distinctViews.stream()
                                .filter(View::tilted)
                                .noneMatch(v -> orientationDistance(v, view) < 1);
        if (weakCell || newScale || newTilt || novelty >= 1.8) return null;
        return "Similar view already captured";
    }

    public void add(View view, long bytes, int corners, long now) {
        if (!canRetain(bytes, corners))
            throw new IllegalStateException("Snapshot storage limit reached");
        snapshots.add(new Snapshot(view, bytes, corners));
        retainedBytes += bytes;
        retainedCorners += corners;
        lastCapture = now;
        rebuild();
    }

    public void remove(int index) {
        var removed = snapshots.remove(index);
        retainedBytes -= removed.bytes();
        retainedCorners -= removed.corners();
        resetStability();
        rebuild();
    }

    private void rebuild() {
        java.util.Arrays.fill(coverage, 0);
        distinctViews.clear();
        for (var snapshot : snapshots) {
            var view = snapshot.view();
            if (view == null || distinctViews.stream().anyMatch(v -> distance(v, view) < 1)) continue;
            distinctViews.add(view);
            boolean[] contributed = new boolean[coverage.length];
            for (var corner : view.corners()) contributed[cell(corner)] = true;
            for (int i = 0; i < coverage.length; i++) if (contributed[i]) coverage[i]++;
        }
    }

    public int[] coverage() {
        return coverage.clone();
    }

    public int distinctCount() {
        return distinctViews.size();
    }

    public int scaleBands() {
        return (int) distinctViews.stream().map(View::scaleBand).distinct().count();
    }

    public int tiltedViews() {
        var orientations = new ArrayList<View>();
        for (var view : distinctViews) {
            if (view.tilted() && orientations.stream().noneMatch(v -> orientationDistance(v, view) < 1))
                orientations.add(view);
        }
        return orientations.size();
    }

    public boolean ready() {
        return distinctCount() >= READY_VIEWS
                && java.util.Arrays.stream(coverage).filter(n -> n >= 3).count() >= 18
                && scaleBands() >= 2
                && tiltedViews() >= 3;
    }

    public String guidance() {
        if (ready()) return "Ready to try calibration";
        int weakest = 0;
        for (int i = 1; i < coverage.length; i++) if (coverage[i] < coverage[weakest]) weakest = i;
        if (java.util.Arrays.stream(coverage).filter(n -> n >= 3).count() < 18) {
            String vertical = weakest / COLUMNS < 2 ? "upper" : "lower";
            String horizontal =
                    weakest % COLUMNS < 2 ? "left" : weakest % COLUMNS >= 4 ? "right" : "center";
            return "Move toward the " + vertical + "-" + horizontal;
        }
        if (scaleBands() < 2) return "Vary the distance; move closer or farther away";
        if (tiltedViews() < 3) return "Tilt the board in different directions";
        return "Collect more views at different positions and angles";
    }

    private static int cell(Corner c) {
        int x = Math.min(COLUMNS - 1, Math.max(0, (int) (c.x() * COLUMNS)));
        int y = Math.min(ROWS - 1, Math.max(0, (int) (c.y() * ROWS)));
        return y * COLUMNS + x;
    }

    private static double movement(View a, View b) {
        if (a == null) return Double.POSITIVE_INFINITY;
        var byId = new java.util.HashMap<Integer, Corner>();
        for (var c : a.corners()) byId.put(c.id(), c);
        double sum = 0;
        int common = 0;
        for (var c : b.corners()) {
            var matched = byId.get(c.id());
            if (matched == null) continue;
            sum += Math.pow(c.x() - matched.x(), 2) + Math.pow(c.y() - matched.y(), 2);
            common++;
        }
        return common < 10 ? Double.POSITIVE_INFINITY : Math.sqrt(sum / common);
    }

    static double distance(View a, View b) {
        double angle = Math.abs(a.rotation() - b.rotation()) % Math.PI;
        angle = Math.min(angle, Math.PI - angle);
        return Math.max(
                orientationDistance(a, b),
                Math.max(
                        Math.hypot(a.x() - b.x(), a.y() - b.y()) / 0.07,
                        Math.max(Math.abs(Math.log(a.scale() / b.scale())) / 0.18, angle / 0.18)));
    }

    private static double orientationDistance(View a, View b) {
        return Math.max(
                Math.abs(a.foreshortening() - b.foreshortening()) / 0.20,
                Math.max(
                        Math.abs(a.skew() - b.skew()) / 0.15,
                        Math.hypot(a.perspectiveX() - b.perspectiveX(), a.perspectiveY() - b.perspectiveY())
                                / 0.12));
    }
}
