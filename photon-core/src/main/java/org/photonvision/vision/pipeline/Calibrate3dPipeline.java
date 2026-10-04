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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.SerializationUtils;
import org.photonvision.vision.calibration.BoardObservation;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.GuidedCalibrationCapture;
import org.photonvision.vision.calibration.GuidedCalibrationMetrics;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.opencv.ImageRotationMode;
import org.photonvision.vision.pipe.CVPipe.CVPipeResult;
import org.photonvision.vision.pipe.impl.CalculateFPSPipe;
import org.photonvision.vision.pipe.impl.Calibrate3dPipe;
import org.photonvision.vision.pipe.impl.Calibrate3dPipe.CalibrationInput;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe.FindBoardCornersPipeResult;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.pipeline.result.CalibrationPipelineResult;
import org.wpilib.util.Pair;

public class Calibrate3dPipeline
        extends CVPipeline<CVPipelineResult, Calibration3dPipelineSettings> {
    // For logging
    private static final Logger logger = new Logger(Calibrate3dPipeline.class, LogGroup.General);

    // Find board corners decides internally between opencv and mrgingham
    private final FindBoardCornersPipe findBoardCornersPipe = new FindBoardCornersPipe();
    private final Calibrate3dPipe calibrate3dPipe = new Calibrate3dPipe();
    private final CalculateFPSPipe calculateFPSPipe = new CalculateFPSPipe();

    // Getter methods have been set for calibrate and takeSnapshot
    private boolean takeSnapshot = false;

    // Output of the corners
    public final List<FindBoardCornersPipeResult> foundCornersList;

    /// Output of the calibration, getter method is set for this.
    private CVPipeResult<CameraCalibrationCoefficients> calibrationOutput;

    private volatile boolean calibrating = false;
    private boolean active;
    private boolean cleanupAfterSolve;
    private String cameraUniqueName = "";
    private String sessionId = "";
    private final GuidedCalibrationCapture capture = new GuidedCalibrationCapture();
    private FindBoardCornersPipe.FindCornersPipeParams sessionParams;
    private int imageWidth;
    private int imageHeight;
    private long lastBroadcast;
    private long confirmationUntil;
    private long captureSequence;
    private String feedback = "Show the calibration board";
    private CameraCalibrationCoefficients pendingCalibration;
    private boolean releaseAfterSolve;
    private Path reviewImageDirectory;
    private Path calibrationImageDirectory;

    private static final FrameThresholdType PROCESSING_TYPE = FrameThresholdType.NONE;

    public Calibrate3dPipeline() {
        super(PROCESSING_TYPE);
        this.settings = new Calibration3dPipelineSettings();
        this.foundCornersList = new ArrayList<>();
    }

    @Override
    protected void setPipeParamsImpl() {
        var params =
                new FindBoardCornersPipe.FindCornersPipeParams(
                        settings.boardHeight,
                        settings.boardWidth,
                        settings.boardType,
                        settings.tagFamily,
                        settings.gridSize,
                        settings.markerSize,
                        settings.streamingFrameDivisor,
                        settings.useOldPattern);
        int width = frameStaticProperties.imageWidth;
        int height = frameStaticProperties.imageHeight;
        if (sessionParams != null
                && (sessionParams.boardWidth() != params.boardWidth()
                        || sessionParams.boardHeight() != params.boardHeight()
                        || sessionParams.type() != params.type()
                        || sessionParams.tagFamily() != params.tagFamily()
                        || sessionParams.gridSize() != params.gridSize()
                        || sessionParams.markerSize() != params.markerSize()
                        || sessionParams.useOldPattern() != params.useOldPattern()
                        || imageWidth != width
                        || imageHeight != height)) {
            clearDataset();
            sessionId = UUID.randomUUID().toString();
            feedback = "Settings changed; show the calibration board again";
            broadcastState();
        }
        sessionParams = params;
        imageWidth = width;
        imageHeight = height;
        findBoardCornersPipe.setParams(params);

        calibrate3dPipe.setParams(
                new Calibrate3dPipe.CalibratePipeParams(
                        settings.boardHeight, settings.boardWidth, settings.gridSize, settings.useMrCal));
    }

    @Override
    public synchronized CVPipelineResult run(Frame frame, QuirkyCamera quirks) {
        // The solver owns the dataset while running; keep preview frames flowing without changing
        // pipes.
        if (calibrating) {
            var color = frame.colorImage.getMat();
            var output = frame.processedImage.getMat();
            if (!color.empty()) {
                if (color.channels() == 1) Imgproc.cvtColor(color, output, Imgproc.COLOR_GRAY2BGR);
                else color.copyTo(output);
                if (active && settings.drawObservationCoverage) drawCoverage(output);
            }
            var result = new CVPipelineResult(frame.sequenceID, 0, 0, List.of(), frame);
            result.setImageCaptureTimestampNanos(frame.timestampNanos);
            return result;
        }
        return super.run(frame, quirks);
    }

    @Override
    protected CVPipelineResult process(Frame frame, Calibration3dPipelineSettings settings) {
        Mat inputColorMat = frame.colorImage.getMat();

        if (this.calibrating || inputColorMat.empty()) {
            return new CVPipelineResult(frame.sequenceID, 0, 0, null, frame);
        }

        if (getSettings().inputImageRotationMode != ImageRotationMode.DEG_0) {
            // All this calibration assumes zero rotation. If we want a rotation, it should
            // be applied at
            // the output
            logger.error(
                    "Input image rotation was non-zero! Calibration wasn't designed to deal with this. Attempting to manually change back to zero");
            getSettings().inputImageRotationMode = ImageRotationMode.DEG_0;
            return new CVPipelineResult(frame.sequenceID, 0, 0, List.of(), frame);
        }

        long sumPipeNanosElapsed = 0L;

        // Check if the frame has chessboard corners
        var outputColorCVMat = new CVMat();
        if (inputColorMat.channels() == 1)
            Imgproc.cvtColor(inputColorMat, outputColorCVMat.getMat(), Imgproc.COLOR_GRAY2BGR);
        else inputColorMat.copyTo(outputColorCVMat.getMat());

        FindBoardCornersPipeResult findBoardResult =
                findBoardCornersPipe.run(Pair.of(inputColorMat, outputColorCVMat.getMat())).output;

        long now = captureTimeNanos();
        boolean manual = takeSnapshot;
        takeSnapshot = false;
        boolean retained = false;
        try {
            if (active) {
                if (findBoardResult == null) {
                    capture.resetStability();
                    feedback = "Not enough corners visible";
                } else if (manual || (settings.guidedCapture && !settings.capturePaused)) {
                    int corners = (int) findBoardResult.imagePoints.total();
                    long bytes = inputColorMat.total() * inputColorMat.elemSize() + 32L * corners;
                    GuidedCalibrationCapture.View view = null;
                    String rejection;
                    if (!capture.canRetain(bytes, corners)) {
                        rejection = "Snapshot storage limit reached; remove views or try calibration";
                    } else {
                        view = GuidedCalibrationMetrics.describe(findBoardResult, settings.gridSize);
                        rejection =
                                manual
                                        ? null
                                        : capture.evaluate(
                                                view,
                                                GuidedCalibrationMetrics.quality(inputColorMat, view),
                                                now,
                                                bytes,
                                                corners);
                    }
                    if (rejection == null) {
                        findBoardResult.inputImage = inputColorMat.clone();
                        foundCornersList.add(findBoardResult);
                        retained = true;
                        capture.add(view, bytes, corners, now);
                        invalidatePendingResult();
                        captureSequence++;
                        confirmationUntil = now + 1_000_000_000L;
                        feedback = "Saved snapshot " + foundCornersList.size();
                        broadcastState();
                    } else {
                        feedback = rejection;
                    }
                } else {
                    capture.resetStability();
                    feedback = settings.capturePaused ? "Auto-capture paused" : "Manual capture";
                }
                if (settings.drawObservationCoverage) drawCoverage(outputColorCVMat.getMat());
                if (now < confirmationUntil) {
                    Imgproc.putText(
                            outputColorCVMat.getMat(),
                            "Saved snapshot " + foundCornersList.size(),
                            new Point(15, 35),
                            Imgproc.FONT_HERSHEY_SIMPLEX,
                            0.8,
                            new Scalar(0, 255, 0),
                            2);
                }
                if (now - lastBroadcast >= 200_000_000L) broadcastState();
            }
        } finally {
            if (!retained && findBoardResult != null) findBoardResult.release();
        }

        var fpsResult = calculateFPSPipe.run(null);
        var fps = fpsResult.output;

        frame.release();

        // Return the drawn chessboard if corners are found, if not, then return the
        // input image.
        return new CalibrationPipelineResult(
                frame.sequenceID,
                sumPipeNanosElapsed,
                fps, // Unused but here in case
                new Frame(
                        frame.sequenceID,
                        new CVMat(),
                        outputColorCVMat,
                        FrameThresholdType.NONE,
                        frame.frameStaticProperties),
                getCornersList());
    }

    synchronized List<List<Point>> getCornersList() {
        return foundCornersList.stream().map(it -> it.imagePoints.toList()).toList();
    }

    public CameraCalibrationCoefficients tryCalibration(Path imageSavePath) {
        return tryCalibration(imageSavePath, sessionId);
    }

    public CameraCalibrationCoefficients tryCalibration(Path imageSavePath, String expectedSession) {
        CalibrationInput input;
        Path solvePath;
        synchronized (this) {
            requireSession(expectedSession);
            if (!active
                    || calibrating
                    || foundCornersList.size() < GuidedCalibrationCapture.MIN_SNAPSHOTS) {
                throw new IllegalStateException("Collect at least ten snapshots before solving");
            }
            calibrating = true;
            settings.capturePaused = true;
            invalidatePendingResult();
            capture.resetStability();
            feedback = "Solving calibration";
            calibrationImageDirectory = imageSavePath.toAbsolutePath().normalize();
            solvePath = calibrationImageDirectory.resolve("session-" + sessionId);
            reviewImageDirectory = solvePath;
            input = new CalibrationInput(List.copyOf(foundCornersList), frameStaticProperties, solvePath);
            broadcastState();
        }
        try {
            try {
                Files.createDirectories(solvePath);
            } catch (IOException e) {
                throw new IllegalStateException("Could not create calibration review image directory", e);
            }
            var output = solve(input);
            synchronized (this) {
                if (!cleanupAfterSolve && active) pendingCalibration = output;
                feedback =
                        pendingCalibration == null
                                ? "Solve failed; add views at different positions and angles"
                                : "Review the result, then apply or collect more views";
                return pendingCalibration;
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                feedback = "Solve failed; add views at different positions and angles";
            }
            throw e;
        } finally {
            synchronized (this) {
                calibrating = false;
                if (cleanupAfterSolve) {
                    cleanupAfterSolve = false;
                    clearDataset();
                }
                if (releaseAfterSolve) releasePipes();
                broadcastState();
            }
        }
    }

    protected CameraCalibrationCoefficients solve(CalibrationInput input) {
        calibrationOutput = calibrate3dPipe.run(input);
        return calibrationOutput.output;
    }

    protected long captureTimeNanos() {
        return System.nanoTime();
    }

    public synchronized void takeSnapshot() {
        if (active && !calibrating) takeSnapshot = true;
    }

    public List<BoardObservation> perViewErrors() {
        return calibrationOutput.output.observations;
    }

    private void clearDataset() {
        foundCornersList.forEach(it -> it.release());
        foundCornersList.clear();
        capture.reset();
        takeSnapshot = false;
        invalidatePendingResult();
        confirmationUntil = 0;
    }

    public synchronized void beginSession(String camera, boolean guided) {
        requireCanStartSession();
        clearDataset();
        cameraUniqueName = camera;
        sessionId = UUID.randomUUID().toString();
        sessionParams = null;
        settings.guidedCapture = guided;
        settings.capturePaused = false;
        active = true;
        feedback = "Show the calibration board";
        broadcastState();
    }

    public synchronized void requireCanStartSession() {
        if (calibrating || released)
            throw new IllegalStateException("Calibration solve is still running or the camera closed");
    }

    public synchronized void finishCalibration() {
        active = false;
        settings.capturePaused = false;
        feedback = "Calibration session ended";
        if (calibrating) cleanupAfterSolve = true;
        else clearDataset();

        broadcastState();
    }

    public synchronized void requireSession(String expectedSession) {
        if (!active || expectedSession == null || !sessionId.equals(expectedSession))
            throw new IllegalStateException("Calibration session changed; refresh the session");
    }

    public synchronized void setPaused(boolean paused) {
        if (calibrating) throw new IllegalStateException("Wait for the current solve to finish");
        if (!paused) invalidatePendingResult();
        settings.capturePaused = paused;
        capture.resetStability();
        feedback = paused ? "Auto-capture paused" : "Hold still";
        broadcastState();
    }

    public synchronized CameraCalibrationCoefficients takePendingCalibration() {
        if (calibrating || pendingCalibration == null)
            throw new IllegalStateException("No reviewed calibration is available to apply");
        var result = pendingCalibration;
        pendingCalibration = null;
        reviewImageDirectory = null; // Ownership transfers to the saved calibration.
        return result;
    }

    public synchronized CameraCalibrationCoefficients pendingCalibration() {
        return pendingCalibration;
    }

    public synchronized UICalibrationData uiState() {
        var state =
                new UICalibrationData(
                        foundCornersList.size(),
                        settings.cameraVideoModeIndex,
                        settings.gridSize,
                        settings.markerSize,
                        settings.boardWidth,
                        settings.boardHeight,
                        settings.boardType,
                        settings.useOldPattern,
                        settings.tagFamily);
        state.cameraUniqueName = cameraUniqueName;
        state.sessionId = sessionId;
        state.active = active;
        state.guidedCapture = settings.guidedCapture;
        state.capturePaused = settings.capturePaused;
        state.solving = calibrating;
        state.coverageColumns = GuidedCalibrationCapture.COLUMNS;
        state.coverageRows = GuidedCalibrationCapture.ROWS;
        state.coverage = capture.coverage();
        state.distinctViews = capture.distinctCount();
        state.scaleBands = capture.scaleBands();
        state.tiltedViews = capture.tiltedViews();
        state.ready = capture.ready();
        state.canSolve = foundCornersList.size() >= GuidedCalibrationCapture.MIN_SNAPSHOTS;
        state.snapshotLimit = GuidedCalibrationCapture.MAX_SNAPSHOTS;
        state.storageLimited =
                feedback.startsWith("Snapshot storage limit")
                        || !capture.canRetain((long) imageWidth * imageHeight + 320, 10);
        state.captureSequence = captureSequence;
        state.recentlyCaptured = confirmationUntil != 0 && captureTimeNanos() < confirmationUntil;
        state.feedback = feedback;
        state.guidance =
                calibrating
                        ? "Wait for the solve to finish"
                        : pendingCalibration != null
                                ? "Review the result before applying it"
                                : settings.capturePaused
                                        ? "Resume capture when ready to collect more views"
                                        : feedback.startsWith("Hold still") || feedback.equals("Move closer")
                                                ? feedback
                                                : feedback.contains("lighting")
                                                        ? "Improve lighting"
                                                        : feedback.contains("corners visible")
                                                                ? "Show more of the calibration board"
                                                                : capture.guidance();
        state.pendingResult =
                pendingCalibration == null ? null : pendingCalibration.cloneWithoutObservations();
        return state;
    }

    public synchronized void broadcastState() {
        Map<String, Object> state = SerializationUtils.objectToHashMap(uiState());

        DataChangeService.getInstance()
                .publishEvent(OutgoingUIEvent.wrappedOf("calibrationData", state));
        lastBroadcast = captureTimeNanos();
    }

    public synchronized boolean removeSnapshot(int index) {
        if (calibrating || index < 0 || index >= foundCornersList.size()) return false;
        try {
            var observation = foundCornersList.remove(index);
            observation.release();
            capture.remove(index);
            invalidatePendingResult();
            feedback = "Snapshot removed";
            broadcastState();
            return true;
        } catch (IndexOutOfBoundsException e) {
            logger.error("Could not remove snapshot at index " + index, e);
            return false;
        }
    }

    public synchronized byte[] snapshotImage(int index) {
        if (index < 0 || index >= foundCornersList.size())
            throw new IllegalArgumentException("Unknown snapshot");
        var encoded = new org.opencv.core.MatOfByte();
        var annotated = new Mat();
        try {
            var observation = foundCornersList.get(index);
            Imgproc.cvtColor(observation.inputImage, annotated, Imgproc.COLOR_GRAY2BGR);
            for (var point : observation.imagePoints.toArray()) {
                Imgproc.circle(annotated, point, 3, new Scalar(0, 255, 0), -1);
            }
            org.opencv.imgcodecs.Imgcodecs.imencode(".jpg", annotated, encoded);
            return encoded.toArray();
        } finally {
            encoded.release();
            annotated.release();
        }
    }

    private void drawCoverage(Mat output) {
        var coverage = capture.coverage();
        for (int row = 0; row < GuidedCalibrationCapture.ROWS; row++) {
            for (int col = 0; col < GuidedCalibrationCapture.COLUMNS; col++) {
                int count = coverage[row * GuidedCalibrationCapture.COLUMNS + col];
                var color =
                        count == 0
                                ? new Scalar(180, 180, 180)
                                : count < 3 ? new Scalar(0, 200, 255) : new Scalar(0, 200, 0);
                double left = col * output.cols() / (double) GuidedCalibrationCapture.COLUMNS;
                double top = row * output.rows() / (double) GuidedCalibrationCapture.ROWS;
                double right = (col + 1) * output.cols() / (double) GuidedCalibrationCapture.COLUMNS - 1;
                double bottom = (row + 1) * output.rows() / (double) GuidedCalibrationCapture.ROWS - 1;
                Imgproc.rectangle(
                        output, new Point(left, top), new Point(right, bottom), color, count >= 3 ? 2 : 1);
                Imgproc.putText(
                        output,
                        (count == 0 ? "M" : count < 3 ? "W" : "S") + " " + count,
                        new Point(left + 5, top + 20),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        0.5,
                        color,
                        1);
            }
        }
    }

    public CameraCalibrationCoefficients cameraCalibrationCoefficients() {
        return calibrationOutput.output;
    }

    @Override
    public synchronized void release() {
        finishCalibration();
        if (calibrating) releaseAfterSolve = true;
        else releasePipes();
        super.release();
    }

    private void releasePipes() {
        findBoardCornersPipe.release();
        calibrate3dPipe.release();
        calculateFPSPipe.release();
    }

    private void invalidatePendingResult() {
        pendingCalibration = null;
        if (reviewImageDirectory == null) return;
        var directory = reviewImageDirectory;
        reviewImageDirectory = null;
        // Only delete this pipeline's generated session directory. Files.walk does not follow links.
        try {
            if (!Files.exists(directory)) return;
            var root = calibrationImageDirectory.toRealPath();
            var target = directory.toRealPath();
            if (!target.startsWith(root)
                    || target.equals(root)
                    || !directory.getFileName().toString().startsWith("session-")) {
                logger.error("Refusing to clean calibration images outside their session directory");
                return;
            }
            try (var files = Files.walk(directory)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.error("Could not clean pending calibration images", e);
        }
    }
}
