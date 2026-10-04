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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.photonvision.jni.LibraryLoader;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe.FindBoardCornersPipeResult;

class GuidedCalibrationMetricsTest {
    @BeforeAll
    static void loadOpenCv() {
        assertTrue(LibraryLoader.loadWpiLibraries());
    }

    static FindBoardCornersPipeResult observation(
            boolean partial, double perspective, boolean collinear) {
        var image = new ArrayList<Point>();
        var object = new ArrayList<Point3>();
        var ids = new ArrayList<Integer>();
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if (partial && (row * 5 + col) % 3 == 0) continue;
                double denominator = 1 + perspective * (col - 2) / 4;
                image.add(
                        new Point(
                                320 + (col - 2) * 40 / denominator,
                                collinear ? 240 : 240 + (row - 2) * 40 / denominator));
                object.add(new Point3(col * 0.025, row * 0.025, 0));
                ids.add(row * 5 + col);
            }
        }
        var ip = new MatOfPoint2f();
        ip.fromList(image);
        var op = new MatOfPoint3f();
        op.fromList(object);
        var cornerIds = new MatOfInt();
        cornerIds.fromList(ids);
        return new FindBoardCornersPipeResult(new Size(640, 480), op, ip, 0, cornerIds);
    }

    @Test
    void homographyHandlesIdentifiedPartialViewsAndPerspectiveWithoutIntrinsics() {
        var complete = observation(false, 0, false);
        var partial = observation(true, 0.6, false);
        var line = observation(false, 0, true);
        try {
            var flat = GuidedCalibrationMetrics.describe(complete, 0.025);
            var tilted = GuidedCalibrationMetrics.describe(partial, 0.025);
            assertNotNull(flat);
            assertNotNull(tilted);
            assertFalse(flat.tilted());
            assertTrue(tilted.tilted());
            assertEquals(16, tilted.corners().size());
            assertEquals(1, tilted.corners().get(0).id());
            assertEquals(0.5, flat.x(), 1e-5);
            assertEquals(40, flat.spacingPixels(), 1e-5);
            assertNull(GuidedCalibrationMetrics.describe(line, 0.025));
        } finally {
            complete.release();
            partial.release();
            line.release();
        }
    }

    @Test
    void localPatchQualityRejectsBlurAndLowContrast() {
        var observation = observation(false, 0, false);
        var sharp = new Mat(480, 640, CvType.CV_8UC1, new Scalar(255));
        var blurred = new Mat();
        var dim = new Mat();
        try {
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 6; col++) {
                    if ((row + col) % 2 == 0)
                        Imgproc.rectangle(
                                sharp,
                                new Point(200 + col * 40, 120 + row * 40),
                                new Point(239 + col * 40, 159 + row * 40),
                                new Scalar(0),
                                -1);
                }
            }
            Imgproc.GaussianBlur(sharp, blurred, new Size(21, 21), 5);
            sharp.convertTo(dim, CvType.CV_8UC1, 0.05, 100);
            var view = GuidedCalibrationMetrics.describe(observation, 0.025);
            var good = GuidedCalibrationMetrics.quality(sharp, view);
            var bad = GuidedCalibrationMetrics.quality(blurred, view);
            var lowContrast = GuidedCalibrationMetrics.quality(dim, view);
            assertTrue(good.contrast() >= 18);
            assertTrue(good.sharpness() >= 0.08);
            assertTrue(bad.sharpness() < 0.08);
            assertTrue(lowContrast.contrast() < 18);
        } finally {
            observation.release();
            sharp.release();
            blurred.release();
            dim.release();
        }
    }
}
