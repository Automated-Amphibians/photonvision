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
import java.util.HashMap;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe.FindBoardCornersPipeResult;

/** Bounded local image quality work; homography descriptors require no intrinsic estimate. */
public final class GuidedCalibrationMetrics {
    private GuidedCalibrationMetrics() {}

    public static GuidedCalibrationCapture.View describe(
            FindBoardCornersPipeResult result, double gridSize) {
        var image = result.imagePoints.toArray();
        var object = result.objectPoints.toArray();
        if (image.length < 10
                || image.length != object.length
                || !Double.isFinite(gridSize)
                || gridSize <= 0) return null;
        int[] ids = result.ids == null ? null : result.ids.toArray();
        if (ids != null && ids.length != image.length) return null;
        double width = result.size.width;
        double height = result.size.height;
        double span = Math.max(width, height);
        if (!Double.isFinite(span) || width <= 0 || height <= 0) return null;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double centerX = 0, centerY = 0;
        var corners = new ArrayList<GuidedCalibrationCapture.Corner>(image.length);
        var gridPoints = new HashMap<Long, Point>();
        for (int i = 0; i < image.length; i++) {
            var p = image[i];
            var o = object[i];
            if (!Double.isFinite(p.x)
                    || !Double.isFinite(p.y)
                    || !Double.isFinite(o.x)
                    || !Double.isFinite(o.y)
                    || p.x < 0
                    || p.x >= width
                    || p.y < 0
                    || p.y >= height) return null;
            minX = Math.min(minX, o.x);
            maxX = Math.max(maxX, o.x);
            minY = Math.min(minY, o.y);
            maxY = Math.max(maxY, o.y);
            centerX += p.x / width;
            centerY += p.y / height;
            corners.add(
                    new GuidedCalibrationCapture.Corner(ids == null ? i : ids[i], p.x / width, p.y / height));
            gridPoints.put(key(Math.round(o.x / gridSize), Math.round(o.y / gridSize)), p);
        }
        double objectSpan = Math.max(maxX - minX, maxY - minY);
        if (maxX - minX < gridSize || maxY - minY < gridSize) return null;
        double spacing = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(image.length, 128); i++) {
            int index = i * image.length / Math.min(image.length, 128);
            long x = Math.round(object[index].x / gridSize), y = Math.round(object[index].y / gridSize);
            for (long[] delta : new long[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                var neighbor = gridPoints.get(key(x + delta[0], y + delta[1]));
                if (neighbor != null)
                    spacing =
                            Math.min(
                                    spacing, Math.hypot(image[index].x - neighbor.x, image[index].y - neighbor.y));
            }
        }
        if (!Double.isFinite(spacing)) return null;

        var source = new MatOfPoint2f();
        var destination = new MatOfPoint2f();
        var points = new MatOfPoint(image);
        var hullIndices = new MatOfInt();
        var hull = new MatOfPoint2f();
        Mat homography = null;
        try {
            Point[] sourcePoints = new Point[object.length];
            Point[] destinationPoints = new Point[image.length];
            for (int i = 0; i < object.length; i++) {
                sourcePoints[i] =
                        new Point(
                                (object[i].x - (minX + maxX) / 2) / objectSpan,
                                (object[i].y - (minY + maxY) / 2) / objectSpan);
                destinationPoints[i] = new Point(image[i].x / span, image[i].y / span);
            }
            source.fromArray(sourcePoints);
            destination.fromArray(destinationPoints);
            homography = Calib3d.findHomography(source, destination, 0);
            if (homography.empty() || !Core.checkRange(homography)) return null;
            Imgproc.convexHull(points, hullIndices);
            var hullPoints = new ArrayList<Point>();
            for (int index : hullIndices.toArray()) hullPoints.add(image[index]);
            hull.fromList(hullPoints);
            double area = Imgproc.contourArea(hull);
            if (area < 1) return null;
            double[] h = new double[9];
            homography.get(0, 0, h);
            double normalization = h[8];
            if (Math.abs(normalization) < 1e-12) return null;
            for (int i = 0; i < h.length; i++) h[i] /= normalization;
            double ux = h[0] - h[2] * h[6], uy = h[3] - h[5] * h[6];
            double vx = h[1] - h[2] * h[7], vy = h[4] - h[5] * h[7];
            double u = Math.hypot(ux, uy), v = Math.hypot(vx, vy);
            double determinant = ux * vy - uy * vx;
            if (u < 1e-8 || v < 1e-8 || Math.abs(determinant) / (u * v) < 0.2) return null;
            return new GuidedCalibrationCapture.View(
                    centerX / image.length,
                    centerY / image.length,
                    Math.sqrt(area / (width * height)),
                    Math.atan2(uy, ux),
                    Math.log(u / v),
                    (ux * vx + uy * vy) / (u * v),
                    h[6],
                    h[7],
                    spacing,
                    corners);
        } finally {
            source.release();
            destination.release();
            points.release();
            hullIndices.release();
            hull.release();
            if (homography != null) homography.release();
        }
    }

    private static long key(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }

    /** The detector already converted its input to grayscale. Sample at most 32 corner patches. */
    public static GuidedCalibrationCapture.Quality quality(
            Mat gray, GuidedCalibrationCapture.View view) {
        if (view == null || gray.channels() != 1) return null;
        var laplacian = new Mat();
        var mean = new MatOfDouble();
        var deviation = new MatOfDouble();
        double contrast = 0, sharpness = 0;
        int count = 0;
        int radius = (int) Math.max(3, Math.min(10, view.spacingPixels() / 3));
        int samples = Math.min(32, view.corners().size());
        try {
            for (int i = 0; i < samples; i++) {
                var point = view.corners().get(i * view.corners().size() / samples);
                int x = (int) Math.round(point.x() * gray.cols()),
                        y = (int) Math.round(point.y() * gray.rows());
                if (x - radius < 0
                        || y - radius < 0
                        || x + radius >= gray.cols()
                        || y + radius >= gray.rows()) continue;
                var patch = gray.submat(new Rect(x - radius, y - radius, 2 * radius + 1, 2 * radius + 1));
                try {
                    Core.meanStdDev(patch, mean, deviation);
                    double std = deviation.toArray()[0];
                    Imgproc.Laplacian(patch, laplacian, CvType.CV_32F);
                    Core.meanStdDev(laplacian, mean, deviation);
                    double lapStd = deviation.toArray()[0];
                    contrast += std;
                    sharpness += lapStd * lapStd / Math.max(1, std * std);
                    count++;
                } finally {
                    patch.release();
                }
            }
            return count < 4
                    ? null
                    : new GuidedCalibrationCapture.Quality(contrast / count, sharpness / count);
        } finally {
            laplacian.release();
            mean.release();
            deviation.release();
        }
    }
}
