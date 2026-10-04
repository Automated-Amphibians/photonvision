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

package org.photonvision.vision.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.photonvision.jni.LibraryLoader;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.CameraLensModel;
import org.photonvision.vision.calibration.JsonMatOfDouble;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.*;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.pipe.impl.Calibrate3dPipe.CalibrationInput;

class GuidedCalibrationPipelineTest {
    @TempDir Path temp;

    @BeforeAll
    static void loadOpenCv() {
        assertTrue(LibraryLoader.loadWpiLibraries());
    }

    static void frame(Calibrate3dPipeline pipeline, int width) {
        frame(pipeline, width, 100);
    }

    static void frame(Calibrate3dPipeline pipeline, int width, int left) {
        var image = new Mat(480, width, CvType.CV_8UC3, new Scalar(255, 255, 255));
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                if ((row + col) % 2 == 0)
                    Imgproc.rectangle(
                            image,
                            new Point(left + col * 35, 80 + row * 35),
                            new Point(left + 34 + col * 35, 114 + row * 35),
                            new Scalar(0, 0, 0),
                            -1);
            }
        }
        var frame =
                new Frame(
                        1,
                        new CVMat(image),
                        new CVMat(),
                        FrameThresholdType.NONE,
                        new FrameStaticProperties(width, 480, 67, null));
        try (var result = pipeline.run(frame, QuirkyCamera.DefaultCamera)) {
            assertFalse(result.inputAndOutputFrame.processedImage.getMat().empty());
        }
    }

    static void collect(Calibrate3dPipeline pipeline, int count) {
        for (int i = 0; i < count; i++) {
            pipeline.takeSnapshot();
            frame(pipeline, 640);
        }
        assertEquals(count, pipeline.uiState().count);
    }

    @Test
    void guidedCaptureAcceptsNovelFramesAndRejectsStationaryDuplicates() {
        class TimedPipeline extends Calibrate3dPipeline {
            long now;

            @Override
            protected long captureTimeNanos() {
                return now;
            }
        }
        try (var pipeline = new TimedPipeline()) {
            pipeline.beginSession("camera-one", true);
            for (int i = 0; i < 16; i++) {
                pipeline.now += 100_000_000L;
                frame(pipeline, 640);
            }
            assertEquals(1, pipeline.uiState().count);
            assertTrue(pipeline.uiState().feedback.contains("Similar view"));
            for (int i = 0; i < 5; i++) {
                pipeline.now += 100_000_000L;
                frame(pipeline, 640, 200);
            }
            assertEquals(2, pipeline.uiState().count);
            pipeline.setPaused(true);
            for (int i = 0; i < 5; i++) {
                pipeline.now += 100_000_000L;
                frame(pipeline, 640, 300);
            }
            assertEquals(2, pipeline.uiState().count);
        }
    }

    @Test
    void manualCaptureWhilePausedRemovalParameterChangeAndCancelReleaseImages() {
        try (var pipeline = new Calibrate3dPipeline()) {
            pipeline.beginSession("camera-one", true);
            String session = pipeline.uiState().sessionId;
            pipeline.setPaused(true);
            collect(pipeline, 2);
            assertEquals(1, pipeline.uiState().distinctViews);
            var retained = pipeline.foundCornersList.get(0);
            assertTrue(pipeline.removeSnapshot(0));
            assertTrue(retained.inputImage.empty());
            assertTrue(retained.objectPoints.empty());
            assertFalse(pipeline.removeSnapshot(-1));
            assertFalse(pipeline.removeSnapshot(20));
            assertTrue(pipeline.uiState().coverage[0] == 0);
            frame(pipeline, 800);
            assertEquals(0, pipeline.uiState().count);
            assertNotEquals(session, pipeline.uiState().sessionId);
            assertThrows(IllegalStateException.class, () -> pipeline.requireSession(session));
            pipeline.takeSnapshot();
            frame(pipeline, 800);
            pipeline.finishCalibration();
            assertFalse(pipeline.uiState().active);
            assertEquals(0, pipeline.uiState().count);
            assertEquals(0, java.util.Arrays.stream(pipeline.uiState().coverage).sum());
        }
    }

    @Test
    void monochromeCameraStillShowsColoredObservationCoverage() {
        try (var pipeline = new Calibrate3dPipeline()) {
            pipeline.beginSession("monochrome-camera", true);
            var image = new Mat(480, 640, CvType.CV_8UC1, new Scalar(255));
            var input =
                    new Frame(
                            1,
                            new CVMat(image),
                            new CVMat(),
                            FrameThresholdType.NONE,
                            new FrameStaticProperties(640, 480, 67, null));
            try (var result = pipeline.run(input, QuirkyCamera.DefaultCamera)) {
                assertEquals(3, result.inputAndOutputFrame.processedImage.getMat().channels());
                assertEquals("Not enough corners visible", pipeline.uiState().feedback);
            }
        }
    }

    @Test
    void failedSolveKeepsDatasetAndPreviewAndEnforcesMinimum() {
        try (var pipeline =
                new Calibrate3dPipeline() {
                    @Override
                    protected CameraCalibrationCoefficients solve(CalibrationInput input) {
                        return null;
                    }
                }) {
            pipeline.beginSession("camera-one", false);
            assertThrows(IllegalStateException.class, () -> pipeline.tryCalibration(temp));
            collect(pipeline, 10);
            assertNull(pipeline.tryCalibration(temp));
            assertEquals(10, pipeline.uiState().count);
            assertFalse(pipeline.uiState().solving);
            assertTrue(pipeline.uiState().active);
            frame(pipeline, 640);
            assertThrows(IllegalStateException.class, pipeline::takePendingCalibration);
        }
    }

    @Test
    void resultNeedsExplicitApplicationAndDatasetChangesInvalidateIt() throws Exception {
        var result =
                new CameraCalibrationCoefficients(
                        new Size(640, 480),
                        new JsonMatOfDouble(3, 3, new double[] {500, 0, 320, 0, 500, 240, 0, 0, 1}),
                        new JsonMatOfDouble(1, 5, new double[5]),
                        null,
                        List.of(),
                        new Size(7, 7),
                        0.025,
                        CameraLensModel.LENSMODEL_OPENCV,
                        null);
        var savedImage = temp.resolve("saved-calibration.jpg");
        Files.writeString(savedImage, "Previously applied image");
        try (var pipeline =
                new Calibrate3dPipeline() {
                    @Override
                    protected CameraCalibrationCoefficients solve(CalibrationInput input) {
                        return result;
                    }
                }) {
            pipeline.beginSession("camera-one", false);
            collect(pipeline, 10);
            assertSame(result, pipeline.tryCalibration(temp));
            var review = temp.resolve("session-" + pipeline.uiState().sessionId);
            Files.writeString(review.resolve("test-image.jpg"), "Pending solve image");
            assertNotNull(pipeline.uiState().pendingResult);
            assertTrue(pipeline.uiState().capturePaused);
            pipeline.removeSnapshot(0);
            assertNull(pipeline.uiState().pendingResult);
            assertFalse(Files.exists(review));
            assertTrue(Files.exists(savedImage));
            pipeline.takeSnapshot();
            frame(pipeline, 640);
            assertSame(result, pipeline.tryCalibration(temp));
            assertSame(result, pipeline.takePendingCalibration());
            pipeline.finishCalibration();
            assertTrue(Files.exists(review)); // Applied image ownership was transferred.
            assertTrue(Files.exists(savedImage));
        }
    }

    @Test
    void cancelDuringSolveDefersCleanupAndPreventsStaleResult() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pipeline =
                new Calibrate3dPipeline() {
                    @Override
                    protected CameraCalibrationCoefficients solve(CalibrationInput input) {
                        entered.countDown();
                        try {
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }
                }) {
            pipeline.beginSession("camera-one", false);
            collect(pipeline, 10);
            var retained = pipeline.foundCornersList.get(0);
            var solve = CompletableFuture.supplyAsync(() -> pipeline.tryCalibration(temp));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                frame(pipeline, 640); // The output preview remains populated while solving.
                assertFalse(pipeline.removeSnapshot(0));
                assertThrows(IllegalStateException.class, () -> pipeline.beginSession("replacement", true));
                pipeline.finishCalibration();
                assertFalse(retained.inputImage.empty());
                assertFalse(pipeline.uiState().active);
            } finally {
                finish.countDown();
            }
            assertNull(solve.get(5, TimeUnit.SECONDS));
            assertTrue(retained.inputImage.empty());
            assertEquals(0, pipeline.uiState().count);
        }
    }
}
