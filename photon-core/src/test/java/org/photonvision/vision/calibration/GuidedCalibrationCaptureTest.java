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
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.photonvision.vision.calibration.GuidedCalibrationCapture.Corner;
import org.photonvision.vision.calibration.GuidedCalibrationCapture.Quality;
import org.photonvision.vision.calibration.GuidedCalibrationCapture.View;

class GuidedCalibrationCaptureTest {
    private static final Quality SHARP = new Quality(80, 0.4);

    private static View view(double x, double y, double scale, double tilt, boolean wholeSensor) {
        var corners = new ArrayList<Corner>();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 6; col++) {
                corners.add(
                        new Corner(
                                row * 6 + col,
                                wholeSensor ? (col + 0.5) / 6 : x + (col - 2.5) * 0.012,
                                wholeSensor ? (row + 0.5) / 4 : y + (row - 1.5) * 0.012));
            }
        }
        return new View(x, y, scale, 0, tilt, 0, 0, 0, 15, corners);
    }

    private static String stable(
            GuidedCalibrationCapture capture, View view, Quality quality, long start) {
        String result = null;
        for (int i = 0; i <= 3; i++)
            result = capture.evaluate(view, quality, start + i * 100_000_000L, 1024, 24);
        return result;
    }

    @Test
    void stationarySequenceCapturesOnceAndDoesNotInflateCoverage() {
        var capture = new GuidedCalibrationCapture();
        var view = view(0.2, 0.2, 0.3, 0, false);
        assertEquals("Hold still", capture.evaluate(view, SHARP, 0, 1024, 24));
        assertNull(stable(capture, view, SHARP, 0));
        capture.add(view, 1024, 24, 300_000_000L);
        assertTrue(stable(capture, view, SHARP, 500_000_000L).contains("cooldown"));
        assertEquals("Similar view already captured", stable(capture, view, SHARP, 1_200_000_000L));
        capture.add(view, 1024, 24, 2_000_000_000L); // Manual duplicate.
        assertEquals(1, capture.distinctCount());
        assertEquals(1, Arrays.stream(capture.coverage()).max().orElseThrow());
        assertFalse(capture.ready());
        assertTrue(Arrays.stream(capture.coverage()).anyMatch(n -> n == 0));
    }

    @Test
    void gatesSmallBlurredLowContrastAndMissingBoardsBeforeNovelty() {
        var capture = new GuidedCalibrationCapture();
        assertTrue(stable(capture, null, SHARP, 0).contains("corners"));
        assertEquals("Move closer", stable(capture, view(0.5, 0.5, 0.05, 0, false), SHARP, 0));
        var view = view(0.5, 0.5, 0.3, 0, false);
        assertTrue(stable(capture, view, new Quality(5, 0.4), 0).contains("lighting"));
        assertTrue(stable(capture, view, new Quality(80, 0.01), 0).contains("blurred"));
        assertEquals(0, capture.distinctCount());
    }

    @Test
    void rejectsMotionSlowDriftAndGapsAndResetsOnPause() {
        var capture = new GuidedCalibrationCapture();
        for (int i = 0; i < 20; i++) {
            assertEquals(
                    "Hold still",
                    capture.evaluate(
                            view(0.2 + i * 0.002, 0.2, 0.3, 0, false), SHARP, i * 100_000_000L, 1024, 24));
        }
        capture.resetStability();
        var stationary = view(0.5, 0.5, 0.3, 0, false);
        assertEquals("Hold still", capture.evaluate(stationary, SHARP, 3_000_000_000L, 1024, 24));
        assertEquals("Hold still", capture.evaluate(stationary, SHARP, 4_000_000_000L, 1024, 24));
        assertNull(stable(capture, stationary, SHARP, 4_000_000_000L));
        capture.resetStability();
        assertEquals("Hold still", capture.evaluate(stationary, SHARP, 4_400_000_000L, 1024, 24));
    }

    @Test
    void spatialScaleAndTiltNoveltyCanEachAddViews() {
        var capture = new GuidedCalibrationCapture();
        capture.add(view(0.2, 0.2, 0.3, 0, false), 1024, 24, 0);
        assertNull(stable(capture, view(0.8, 0.8, 0.3, 0, false), SHARP, 1_000_000_000L));
        assertNull(stable(capture, view(0.2, 0.2, 0.5, 0, false), SHARP, 2_000_000_000L));
        assertNull(stable(capture, view(0.2, 0.2, 0.3, 0.5, false), SHARP, 3_000_000_000L));
    }

    @Test
    void straightOnCoverageStillRequestsTiltAndRemovalRecomputesReadiness() {
        var capture = new GuidedCalibrationCapture();
        for (int i = 0; i < 30; i++) {
            capture.add(
                    view(0.2 + (i % 5) * 0.15, 0.2 + (i / 5) * 0.1, i % 2 == 0 ? 0.2 : 0.5, 0, true),
                    1024,
                    24,
                    i);
        }
        assertEquals(30, capture.distinctCount());
        assertFalse(capture.ready());
        assertEquals("Tilt the board in different directions", capture.guidance());
        capture.add(view(0.5, 0.5, 0.3, 0.4, true), 1024, 24, 40);
        capture.add(view(0.5, 0.5, 0.3, -0.4, true), 1024, 24, 41);
        capture.add(view(0.5, 0.5, 0.3, 0.8, true), 1024, 24, 42);
        assertTrue(capture.ready());
        capture.remove(32);
        assertFalse(capture.ready());
        capture.reset();
        assertEquals(0, Arrays.stream(capture.coverage()).sum());
        assertEquals(0, capture.scaleBands());
    }

    @Test
    void snapshotByteAndPointLimitsRecoverAfterRemoval() {
        var capture = new GuidedCalibrationCapture();
        var view = view(0.5, 0.5, 0.3, 0, false);
        capture.add(view, GuidedCalibrationCapture.MAX_BYTES - 1, 24, 0);
        assertFalse(capture.canRetain(2, 24));
        capture.remove(0);
        capture.add(view, 1024, GuidedCalibrationCapture.MAX_CORNERS, 0);
        assertFalse(capture.canRetain(1, 1));
        capture.reset();
        for (int i = 0; i < GuidedCalibrationCapture.MAX_SNAPSHOTS; i++) capture.add(view, 1024, 24, i);
        assertFalse(capture.canRetain(1, 1));
        capture.remove(0);
        assertTrue(capture.canRetain(1, 1));
    }
}
