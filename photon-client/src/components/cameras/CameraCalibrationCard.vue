<script setup lang="ts">
import { computed, ref, watch, watchEffect } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import {
  CalibrationBoardTypes,
  CalibrationPaperTypes,
  CalibrationTagFamilies,
  type VideoFormat
} from "@/types/SettingTypes";
import MonoLogo from "@/assets/images/logoMono.png";
import PvSlider from "@/components/common/pv-slider.vue";
import { useStateStore } from "@/stores/StateStore";
import PvSwitch from "@/components/common/pv-switch.vue";
import PvSelect from "@/components/common/pv-select.vue";
import PvNumberInput from "@/components/common/pv-number-input.vue";
import { WebsocketPipelineType } from "@/types/WebsocketDataTypes";
import {
  arucoTagDictionaryFor,
  arucoTagFamilyNameFor,
  getResolutionString,
  paperDimensionsFor,
  resolutionsAreEqual
} from "@/lib/PhotonUtils";
import CameraCalibrationInfoCard from "@/components/cameras/CameraCalibrationInfoCard.vue";
import { useSettingsStore } from "@/stores/settings/GeneralSettingsStore";
import { useTheme } from "vuetify";
import TooltippedLabel from "@/components/common/pv-tooltipped-label.vue";
import { length, type Length } from "@adam-rocska/units-and-measurement/length";
import axios from "axios";

const PromptRegular = import("@/assets/fonts/PromptRegular");
const jspdf = import("jspdf").then(async (jspdf) => {
  await import("svg2pdf.js");
  return jspdf;
});
const arucoMarker = import("aruco-marker");

const theme = useTheme();
const session = computed(() => useStateStore().currentCalibrationSession);
const snapshotCount = computed(() => session.value?.count ?? 0);
const guidedCapture = ref(false);
const pendingActions = ref(0);
const actionBusy = computed(() => pendingActions.value > 0);
const actionError = ref("");
const showSnapshots = ref(false);
const snapshotIndex = ref(0);
const showReview = ref(false);
const sessionVideoFormat = computed(() =>
  useCameraSettingsStore().currentCameraSettings.validVideoFormats.find(
    (format) => format.index === session.value?.videoModeIndex
  )
);
const snapshotUrl = computed(() => {
  const query = new URLSearchParams({
    cameraUniqueName: useStateStore().currentCameraUniqueName,
    sessionId: session.value?.sessionId ?? "",
    snapshotIndex: String(snapshotIndex.value),
    revision: `${snapshotCount.value}-${session.value?.captureSequence ?? 0}`
  });
  return `${axios.defaults.baseURL}/calibration/session/snapshot?${query}`;
});

const sessionAction = async (action: "pause" | "resume" | "snapshot" | "remove" | "solve" | "apply" | "cancel") => {
  const camera = useStateStore().currentCameraUniqueName;
  const sessionId = session.value?.sessionId;
  pendingActions.value++;
  actionError.value = "";
  try {
    const data = await useCameraSettingsStore().calibrationSessionAction(action, snapshotIndex.value);
    if (camera !== useStateStore().currentCameraUniqueName || sessionId !== session.value?.sessionId) return;
    snapshotIndex.value = Math.max(0, Math.min(snapshotIndex.value, data.count - 1));
    if (action === "solve" && data.active) {
      showReview.value = Boolean(data.pendingResult);
      if (!data.pendingResult)
        actionError.value = "Solve failed. Your snapshots were kept; add views at different positions and angles.";
    }
    if (action === "apply" || action === "cancel") {
      showReview.value = false;
      showSnapshots.value = false;
    }
  } catch (error) {
    if (camera !== useStateStore().currentCameraUniqueName || sessionId !== session.value?.sessionId) return;
    if (action === "solve" && !session.value?.active) return;
    actionError.value =
      axios.isAxiosError(error) && typeof error.response?.data === "string"
        ? error.response.data
        : "Could not update calibration. Check the connection and try again.";
  } finally {
    pendingActions.value--;
  }
};

const reviewSnapshots = async () => {
  await sessionAction("pause");
  if (!actionError.value) showSnapshots.value = true;
};

watch(
  () => [useStateStore().currentCameraUniqueName, useStateStore().backendConnected] as const,
  ([camera, connected]) => {
    showSnapshots.value = false;
    showReview.value = false;
    actionError.value = "";
    if (connected && camera)
      void useCameraSettingsStore()
        .refreshCalibrationSession(camera)
        .catch(() => {});
  },
  { immediate: true }
);
watch(
  () => session.value?.sessionId,
  () => {
    snapshotIndex.value = 0;
    showReview.value = false;
    showSnapshots.value = false;
  }
);

const settingsValid = ref(true);
watch(
  () => session.value?.pendingResult,
  (result) => {
    if (!result) showReview.value = false;
  }
);

const getUniqueVideoFormatsByResolution = (): VideoFormat[] => {
  const uniqueResolutions: VideoFormat[] = [];
  if (useCameraSettingsStore().currentCameraSettings.validVideoFormats.length === 0) return uniqueResolutions;
  useCameraSettingsStore().currentCameraSettings.validVideoFormats.forEach((format) => {
    const index = uniqueResolutions.findIndex((v) => resolutionsAreEqual(v.resolution, format.resolution));
    const contains = index !== -1;
    let skip = false;
    if (contains && format.fps > uniqueResolutions[index].fps) {
      uniqueResolutions.splice(index, 1);
    } else if (contains) {
      skip = true;
    }

    if (!skip) {
      const calib = useCameraSettingsStore().getCalibrationCoeffs(format.resolution);

      // minPixelCount is the multiplied area of a 640x480 (the minimum for proper calibration) resolution
      const minPixelCount = 640 * 480;
      const resArea = format.resolution.width * format.resolution.height;

      if (calib !== undefined) {
        // Mean overall reprojection error
        // Calculated as average of each observation's mean error
        if (calib.meanErrors.length)
          format.mean = calib.meanErrors.reduce((a, b) => a + b, 0) / calib.meanErrors.length;
        else format.mean = NaN;

        format.horizontalFOV =
          2 * Math.atan2(format.resolution.width / 2, calib.cameraIntrinsics.data[0]) * (180 / Math.PI);
        format.verticalFOV =
          2 * Math.atan2(format.resolution.height / 2, calib.cameraIntrinsics.data[4]) * (180 / Math.PI);
        format.diagonalFOV =
          2 *
          Math.atan2(
            Math.sqrt(
              format.resolution.width ** 2 +
                (format.resolution.height / (calib.cameraIntrinsics.data[4] / calib.cameraIntrinsics.data[0])) ** 2
            ) / 2,
            calib.cameraIntrinsics.data[0]
          ) *
          (180 / Math.PI);
      }

      if (resArea >= minPixelCount) {
        uniqueResolutions.push(format);
      }
    }
  });
  uniqueResolutions.sort(
    (a, b) => b.resolution.width + b.resolution.height - (a.resolution.width + a.resolution.height)
  );
  return uniqueResolutions;
};

const getUniqueVideoResolutionStrings = (): { name: string; value: number }[] =>
  getUniqueVideoFormatsByResolution().map<{ name: string; value: number }>((f) => ({
    name: `${getResolutionString(f.resolution)}`,
    value: f.index || 0 // Index won't ever be undefined
  }));
const calibrationDivisors = computed(() =>
  [1, 2, 4].filter((v) => {
    const currentRes = useCameraSettingsStore().currentVideoFormat?.resolution;
    if (!currentRes) {
      return 0;
    }
    return (currentRes.width / v >= 300 && currentRes.height / v >= 220) || v === 1;
  })
);

const uniqueVideoResolutionIndex = ref(getUniqueVideoResolutionStrings()?.[0]?.value);

// Use a watchEffect so the value is populated/reacts when the stores become available or update.
// This avoids trying to index into an array that may be empty during page reload.
watchEffect(() => {
  if (session.value?.active) {
    useStateStore().calibrationData.videoFormatIndex = session.value.videoModeIndex;
    uniqueVideoResolutionIndex.value = session.value.videoModeIndex;
    return;
  }
  const names = useCameraSettingsStore().currentCameraSettings.validVideoFormats.map((f) =>
    getResolutionString(f.resolution)
  );
  const currentFormatIndex = useCameraSettingsStore().currentVideoFormat?.index ?? 0;
  // Checks if the current resolution is present in the list of valid formats, if not defaults to the last index (which is usually the highest resolution)
  const currentIndex =
    getUniqueVideoResolutionStrings()
      .map((x) => x.name)
      .find((n) => n === names[currentFormatIndex]) !== undefined
      ? currentFormatIndex
      : names.length - 1;
  useStateStore().calibrationData.videoFormatIndex = currentIndex;
  uniqueVideoResolutionIndex.value = currentIndex;
});
const dimensionUnit = ref<"in" | "mm">("mm");
const squareSize = ref(25);
const markerSize = ref(18);
const patternWidth = ref(8);
const patternHeight = ref(8);
const boardType = ref<CalibrationBoardTypes>(CalibrationBoardTypes.ChArUco);
const useOldPattern = ref(false);
const tagFamily = ref<CalibrationTagFamilies>(CalibrationTagFamilies.Dict_4X4_1000);
const paperType = ref<CalibrationPaperTypes>(CalibrationPaperTypes.Letter);
const paperOrientation = ref<"portrait" | "landscape">("portrait");

watch(
  () => session.value?.sessionId,
  () => {
    const current = session.value;
    if (!current?.active) return;
    squareSize.value = length.m(current.squareSizeMeters)[dimensionUnit.value].value;
    markerSize.value = length.m(current.markerSizeMeters)[dimensionUnit.value].value;
    patternWidth.value = current.patternWidth;
    patternHeight.value = current.patternHeight;
    boardType.value = current.boardType;
    useOldPattern.value = current.useOldPattern;
    tagFamily.value = current.tagFamily;
  },
  { immediate: true }
);

watch(dimensionUnit, (value, oldValue) => {
  squareSize.value = length[oldValue](squareSize.value)[value].value;
  markerSize.value = length[oldValue](markerSize.value)[value].value;
});

const dimensionStep = computed(() => (dimensionUnit.value === "mm" ? 0.1 : 0.01));

const adaptivePaperDimensions = (): [Length, Length] => {
  return [
    length[dimensionUnit.value](squareSize.value * patternWidth.value + 1),
    length[dimensionUnit.value](squareSize.value * patternHeight.value + 3)
  ];
};

// Emperical testing - with stack size limit of 1MB, we can handle at -least- 700k points
const tooManyPoints = computed(() => snapshotCount.value * patternWidth.value * patternHeight.value > 700000);

const downloadCalibBoard = async () => {
  const { jsPDF } = await jspdf;
  const { font } = await PromptRegular;

  const paperDimensions =
    paperType.value === CalibrationPaperTypes.Adaptive
      ? adaptivePaperDimensions()
      : paperDimensionsFor(paperType.value);

  const doc = new jsPDF({
    unit: "in",
    ...(paperType.value === CalibrationPaperTypes.Adaptive
      ? {
          format: paperDimensions.map((dim) => dim.in.value),
          orientation: paperDimensions[0].value < paperDimensions[1].value ? "p" : "l"
        }
      : {
          format: CalibrationPaperTypes[paperType.value],
          orientation: paperOrientation.value
        })
  });

  doc.addFileToVFS("Prompt-Regular.tff", font);
  doc.addFont("Prompt-Regular.tff", "Prompt-Regular", "normal");
  doc.setFont("Prompt-Regular");
  doc.setFontSize(12);

  const paperWidth = paperDimensions[paperOrientation.value === "portrait" ? 0 : 1].in.value;
  const paperHeight = paperDimensions[paperOrientation.value === "portrait" ? 1 : 0].in.value;

  const squareSizeIn = length[dimensionUnit.value](squareSize.value).in.value;
  const chessboardStartX = (paperWidth - patternWidth.value * squareSizeIn) / 2;
  const chessboardStartY = (paperHeight - patternHeight.value * squareSizeIn) / 2;

  switch (boardType.value) {
    case CalibrationBoardTypes.Chessboard:
      // This branch is inaccessible
      console.error("Chessboard generation is not supported");
      return;

    case CalibrationBoardTypes.ChArUco:
      const markerSizeIn = length[dimensionUnit.value](markerSize.value).in.value;

      const { arucoToSVGString } = await arucoMarker;
      // ChArUco boards place ArUco tags in reading order over a chessboard with a black square in the top left
      let markerIndex = 0;
      const squarePadding = (squareSizeIn - markerSizeIn) / 2;
      for (let squareY = 0; squareY < patternHeight.value; squareY++) {
        for (let squareX = 0; squareX < patternWidth.value; squareX++) {
          const xPos = chessboardStartX + squareX * squareSizeIn;
          const yPos = chessboardStartY + squareY * squareSizeIn;

          // Draw black squares on the even tiles and ArUco markers on the odd tiles
          // Parity is even in the top left corner unless using the old pattern, which starts in the bottom left corner
          if ((squareY + (useOldPattern.value ? patternHeight.value - 1 : 0)) % 2 === squareX % 2) {
            doc.rect(xPos, yPos, squareSizeIn, squareSizeIn, "F");
          } else {
            await doc.svg(
              new DOMParser()
                .parseFromString(
                  arucoToSVGString(markerIndex++, undefined, await arucoTagDictionaryFor(tagFamily.value)),
                  "image/svg+xml"
                )
                .getElementsByTagName("svg")[0],
              {
                x: xPos + squarePadding,
                y: yPos + squarePadding,
                width: markerSizeIn,
                height: markerSizeIn
              }
            );
          }
        }
      }
      doc.text(
        `${patternWidth.value} x ${patternHeight.value} | ${arucoTagFamilyNameFor(tagFamily.value)}\n` +
          `${squareSize.value}${dimensionUnit.value} squares | ${markerSize.value}${dimensionUnit.value} markers` +
          (useOldPattern.value && patternHeight.value % 2 === 0 ? "\nOld OpenCV Pattern" : ""),
        paperWidth - 1,
        1.0,
        { maxWidth: (paperWidth - 2.0) / 2, align: "right" }
      );

      break;
  }

  // Draw ruler pattern
  const lineStartX = 1.0;
  const lineWidth = paperWidth - 2 * lineStartX;
  const lineY = paperHeight - 0.75;

  doc.setLineWidth(0.01);
  doc.line(lineStartX, lineY, lineStartX + lineWidth, lineY);

  for (let tickMeasure = 0; tickMeasure <= lineWidth; tickMeasure++) {
    const tickX = lineStartX + tickMeasure;
    doc.line(tickX, lineY, tickX, lineY + 0.25);
    doc.text(`${tickMeasure}${tickMeasure === 0 ? " in" : ""}`, tickX + 0.1, lineY + 0.2);
  }

  for (let tickMeasure = 0; tickMeasure <= length.in(lineWidth).mm.value; tickMeasure += 20) {
    const tickX = lineStartX + length.mm(tickMeasure).in.value;
    doc.line(tickX, lineY, tickX, lineY - 0.25);
    doc.text(`${tickMeasure}${tickMeasure === 0 ? " mm" : ""}`, tickX + 0.1, lineY - 0.05);
  }

  // Add branding
  const logoImage = new Image();
  logoImage.src = MonoLogo;
  doc.addImage(logoImage, "PNG", 1.0, 0.75, 1.4, 0.5);

  doc.save(`calibrationTarget-${CalibrationBoardTypes[boardType.value]}.pdf`);
};

const isCalibrating = computed(
  () => useCameraSettingsStore().currentCameraSettings.currentPipelineIndex === WebsocketPipelineType.Calib3d.valueOf()
);

const startCalibration = () => {
  useCameraSettingsStore().startPnPCalibration({
    squareSizeMeters: length[dimensionUnit.value](squareSize.value).m.value,
    markerSizeMeters: length[dimensionUnit.value](markerSize.value).m.value,
    patternHeight: patternHeight.value,
    patternWidth: patternWidth.value,
    boardType: boardType.value,
    useOldPattern: useOldPattern.value,
    tagFamily: tagFamily.value,
    guidedCapture: guidedCapture.value
  });
  // The Start PnP method already handles updating the backend so only a store update is required
  useCameraSettingsStore().currentCameraSettings.currentPipelineIndex = WebsocketPipelineType.Calib3d;
  // isCalibrating.value = true;
  actionError.value = "";
};

const drawAllSnapshots = ref(true);

const bypassVal = ref(false);
const minCount = computed(() => (session.value?.guidedCapture ? 30 : bypassVal.value ? 10 : 100));
const hasEnoughImages = computed(() => snapshotCount.value >= minCount.value);
const canTryCalibration = computed(
  () =>
    session.value?.canSolve && (session.value.guidedCapture || hasEnoughImages.value || session.value.storageLimited)
);

const showCalDialog = ref(false);
const selectedVideoFormat = ref<VideoFormat | undefined>(undefined);
const setSelectedVideoFormat = (format: VideoFormat) => {
  selectedVideoFormat.value = format;
  showCalDialog.value = true;
};
</script>

<template>
  <div>
    <v-card class="mb-3 rounded-12" color="surface" dark>
      <v-card-title>Camera Calibration</v-card-title>
      <v-card-text v-if="!isCalibrating" class="pb-0">
        <div class="pb-3">
          <tooltipped-label
            label="Curent Calibrations"
            icon="mdi-information"
            location="top"
            tooltip="Click on a resolution to view detailed calibration information and import/export a calibration."
          />
        </div>
        <v-table fixed-header height="100%" density="compact">
          <thead>
            <tr>
              <th>Resolution</th>
              <th>Mean Error</th>
              <th>Horizontal FOV</th>
              <th>Vertical FOV</th>
              <th>Diagonal FOV</th>
            </tr>
          </thead>
          <tbody style="cursor: pointer">
            <v-tooltip
              v-for="(value, index) in getUniqueVideoFormatsByResolution()"
              :key="index"
              transition=""
              location="bottom"
              :open-delay="100"
            >
              <template #activator="{ props }">
                <tr :key="index" v-bind="props" @click="setSelectedVideoFormat(value)">
                  <td>{{ getResolutionString(value.resolution) }}</td>
                  <td>
                    {{
                      value.mean !== undefined ? (isNaN(value.mean) ? "Unknown" : value.mean.toFixed(2) + "px") : "-"
                    }}
                  </td>
                  <td>{{ value.horizontalFOV !== undefined ? value.horizontalFOV.toFixed(2) + "°" : "-" }}</td>
                  <td>{{ value.verticalFOV !== undefined ? value.verticalFOV.toFixed(2) + "°" : "-" }}</td>
                  <td>{{ value.diagonalFOV !== undefined ? value.diagonalFOV.toFixed(2) + "°" : "-" }}</td>
                </tr>
              </template>
              <span>View calibration information</span>
            </v-tooltip>
          </tbody>
        </v-table>
      </v-card-text>
      <v-card-text class="pt-0">
        <div v-if="useCameraSettingsStore().isConnected && !isCalibrating" class="d-flex flex-column">
          <v-card-subtitle v-if="!isCalibrating" class="pl-0 pb-3 pt-4 opacity-100"
            >Configure New Calibration</v-card-subtitle
          >
          <v-form v-model="settingsValid">
            <pv-select
              v-model="uniqueVideoResolutionIndex"
              label="Resolution"
              :select-cols="8"
              :disabled="isCalibrating"
              tooltip="Resolution to calibrate at (you will have to calibrate every resolution you use 3D mode on)"
              :items="getUniqueVideoResolutionStrings()"
              @update:model-value="(value) => (useStateStore().calibrationData.videoFormatIndex = value)"
            />
            <pv-select
              v-model="boardType"
              label="Board Type"
              tooltip="Calibration board pattern to use"
              :select-cols="8"
              :items="[
                { value: CalibrationBoardTypes.ChArUco, name: 'ChArUco' },
                { value: CalibrationBoardTypes.Chessboard, name: 'Chessboard' }
              ]"
              :disabled="isCalibrating"
            />
            <v-alert
              v-if="boardType !== CalibrationBoardTypes.ChArUco"
              closable
              density="compact"
              variant="tonal"
              color="warning"
              icon="mdi-alert-box"
              text="The usage of chessboards can result in bad calibration results if multiple
              similar images are taken. We strongly recommend that teams use ChArUco boards instead!"
            />
            <pv-select
              v-if="boardType !== CalibrationBoardTypes.ChArUco"
              v-model="useCameraSettingsStore().currentPipelineSettings.streamingFrameDivisor"
              label="Decimation"
              tooltip="Resolution to which camera frames are downscaled for detection. Calibration still uses full-res"
              :items="calibrationDivisors"
              :select-cols="8"
              @update:modelValue="
                (v) => useCameraSettingsStore().changeCurrentPipelineSetting({ streamingFrameDivisor: +v }, false)
              "
            />
            <pv-select
              v-if="boardType === CalibrationBoardTypes.ChArUco"
              v-model="tagFamily"
              label="Tag Family"
              tooltip="Dictionary of ArUco markers on the ChArUco board"
              :select-cols="8"
              :items="
                [
                  CalibrationTagFamilies.Dict_4X4_1000,
                  CalibrationTagFamilies.Dict_5X5_1000,
                  CalibrationTagFamilies.Dict_6X6_1000,
                  CalibrationTagFamilies.Dict_7X7_1000
                ].map((family) => ({ value: family, name: arucoTagFamilyNameFor(family) }))
              "
              :disabled="isCalibrating"
            />
            <pv-select
              v-model="dimensionUnit"
              label="Dimension Unit"
              tooltip="Units used for pattern spacing and marker size inputs"
              :select-cols="8"
              :items="[
                { value: 'mm', name: 'Millimeters' },
                { value: 'in', name: 'Inches' }
              ]"
              :disabled="isCalibrating"
            />
            <pv-number-input
              v-model="squareSize"
              :label="`Pattern Spacing (${dimensionUnit})`"
              :tooltip="`Spacing between pattern features in ${dimensionUnit === 'mm' ? 'millimeters' : 'inches'}`"
              :disabled="isCalibrating"
              :rules="[(v) => v > 0 || 'Size must be positive']"
              :label-cols="4"
              :step="dimensionStep"
            />
            <pv-number-input
              v-if="boardType === CalibrationBoardTypes.ChArUco"
              v-model="markerSize"
              :label="`Marker Size (${dimensionUnit})`"
              :tooltip="`Size of the tag markers in ${dimensionUnit === 'mm' ? 'millimeters' : 'inches'}; must be smaller than pattern spacing`"
              :disabled="isCalibrating"
              :rules="[(v) => v > 0 || 'Size must be positive']"
              :label-cols="4"
              :step="dimensionStep"
            />
            <pv-number-input
              v-model="patternWidth"
              label="Board Width (squares)"
              tooltip="Width of the board in dots or chessboard squares"
              :disabled="isCalibrating"
              :rules="[(v) => v >= 4 || 'Width must be at least 4']"
              :label-cols="4"
            />
            <pv-number-input
              v-model="patternHeight"
              label="Board Height (squares)"
              tooltip="Height of the board in dots or chessboard squares"
              :disabled="isCalibrating"
              :rules="[(v) => v >= 4 || 'Height must be at least 4']"
              :label-cols="4"
            />
            <pv-switch
              v-if="boardType === CalibrationBoardTypes.ChArUco"
              v-model="useOldPattern"
              label="Old OpenCV Pattern"
              :disabled="isCalibrating"
              tooltip="If enabled, Photon will use the old OpenCV pattern for calibration (the top left square is a marker)."
              :label-cols="4"
            />
            <pv-select
              v-model="paperType"
              label="Paper Type"
              tooltip="Size of paper used when exporting a calibration board."
              :items="
                [
                  CalibrationPaperTypes.Letter,
                  CalibrationPaperTypes.Legal,
                  CalibrationPaperTypes.Tabloid,
                  CalibrationPaperTypes.A4,
                  CalibrationPaperTypes.A3,
                  CalibrationPaperTypes.A2,
                  CalibrationPaperTypes.Adaptive
                ].map((paperType) => {
                  const dimensions =
                    paperType === CalibrationPaperTypes.Adaptive
                      ? adaptivePaperDimensions()
                      : paperDimensionsFor(paperType);
                  return {
                    value: paperType,
                    name: `${CalibrationPaperTypes[paperType]} (${dimensions[0].value} ${dimensions[0].unit} x ${dimensions[1].value} ${dimensions[1].unit})`
                  };
                })
              "
              :select-cols="8"
            />
            <pv-select
              v-model="paperOrientation"
              label="Paper Orientation"
              :disabled="paperType === CalibrationPaperTypes.Adaptive"
              tooltip="Orientation of paper used when exporting a calibration board."
              :items="[
                { value: 'landscape', name: 'Landscape' },
                { value: 'portrait', name: 'Portrait' }
              ]"
              :select-cols="8"
            />
          </v-form>
        </div>
        <div v-if="isCalibrating">
          <v-alert
            class="mb-3"
            variant="tonal"
            :color="session?.recentlyCaptured ? 'success' : 'info'"
            role="status"
            aria-live="polite"
          >
            <div class="calibration-instructions">
              <strong class="calibration-guidance">{{ session?.guidance || session?.feedback }}</strong>
              <div class="calibration-feedback">
                {{
                  session?.recentlyCaptured
                    ? `Saved snapshot ${snapshotCount}`
                    : session?.guidance !== session?.feedback
                      ? session?.feedback
                      : ""
                }}
              </div>
            </div>
          </v-alert>
          <div v-if="session?.guidedCapture" class="mb-3">
            <v-btn
              :disabled="actionBusy || session.solving"
              @click="sessionAction(session.capturePaused ? 'resume' : 'pause')"
            >
              {{ session.capturePaused ? "Resume auto-capture" : "Pause auto-capture" }}
            </v-btn>
          </div>
          <p class="mb-2">Observation coverage: M = Missing, W = Weak (1–2 distinct views), S = Sampled (3+).</p>
          <p class="mb-3">
            {{ session?.distinctViews ?? 0 }} distinct views · {{ session?.scaleBands ?? 0 }} scale bands ·
            {{ session?.tiltedViews ?? 0 }} tilted views
          </p>
          <v-alert v-if="session?.ready" class="mb-3" color="success" variant="tonal">
            Ready to try calibration. Review the solve before applying it; observation coverage does not guarantee
            accuracy.
          </v-alert>
          <v-btn class="mb-3" :disabled="!snapshotCount || actionBusy || session?.solving" @click="reviewSnapshots"
            >Review / remove snapshots</v-btn
          >
          <pv-switch
            v-model="drawAllSnapshots"
            label="Draw Collected Corners"
            :switch-cols="8"
            tooltip="Draw all snapshots"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ drawAllSnapshots: args }, false)
            "
          />
          <pv-switch
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraAutoExposure"
            label="Auto Exposure"
            :label-cols="4"
            tooltip="Enables or Disables camera automatic adjustment for current lighting conditions"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraAutoExposure: args }, false)
            "
          />
          <pv-slider
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraExposureRaw"
            :disabled="useCameraSettingsStore().currentCameraSettings.pipelineSettings.cameraAutoExposure"
            label="Exposure"
            tooltip="Directly controls how long the camera shutter remains open. Units are dependant on the underlying driver."
            :min="useCameraSettingsStore().minExposureRaw"
            :max="useCameraSettingsStore().maxExposureRaw"
            :slider-cols="8"
            :step="1"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraExposureRaw: args }, false)
            "
          />
          <pv-slider
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraBrightness"
            label="Brightness"
            :min="0"
            :max="100"
            :slider-cols="8"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraBrightness: args }, false)
            "
          />
          <pv-slider
            v-if="useCameraSettingsStore().currentPipelineSettings.cameraGain >= 0"
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraGain"
            label="Camera Gain"
            tooltip="Controls camera gain, similar to brightness"
            :min="0"
            :max="100"
            :slider-cols="8"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraGain: args }, false)
            "
          />
          <pv-slider
            v-if="useCameraSettingsStore().currentPipelineSettings.cameraRedGain !== -1"
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraRedGain"
            label="Red AWB Gain"
            :min="0"
            :max="100"
            :slider-cols="8"
            tooltip="Controls red automatic white balance gain, which affects how the camera captures colors in different conditions"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraRedGain: args }, false)
            "
          />
          <pv-slider
            v-if="useCameraSettingsStore().currentPipelineSettings.cameraBlueGain !== -1"
            v-model="useCameraSettingsStore().currentPipelineSettings.cameraBlueGain"
            label="Blue AWB Gain"
            :min="0"
            :max="100"
            :slider-cols="8"
            tooltip="Controls blue automatic white balance gain, which affects how the camera captures colors in different conditions"
            @update:modelValue="
              (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraBlueGain: args }, false)
            "
          />
        </div>
        <v-alert
          closable
          density="compact"
          class="mb-5"
          :variant="theme.global.current.value.dark ? 'tonal' : 'elevated'"
          :color="useSettingsStore().general.mrCalWorking ? 'buttonPassive' : 'error'"
          :icon="useSettingsStore().general.mrCalWorking ? 'mdi-check' : 'mdi-close'"
          :text="
            useSettingsStore().general.mrCalWorking
              ? 'Mrcal was successfully loaded and will be used!'
              : 'MrCal failed to load, check journalctl logs for details.'
          "
        />
        <div v-if="isCalibrating" class="d-flex justify-center align-center pb-5">
          <v-chip
            :variant="theme.global.current.value.dark ? 'tonal' : 'elevated'"
            label
            :color="hasEnoughImages ? 'buttonPassive' : 'light-grey'"
          >
            Snapshots: {{ snapshotCount }} · Recommended:
            {{ minCount }}
          </v-chip>
          <v-spacer />
          <pv-switch
            v-if="!session?.guidedCapture"
            v-model="bypassVal"
            color="error"
            hide-details
            class="ml-4"
            label="Bypass minimum"
            :label-cols="6"
            :switch-cols="6"
            tooltip="Bypass the minimum recommended amount of snapshots for a calibration. Should only be used for dev work or temporary tests not competitions. Still requires 10 images to calibrate."
          />
        </div>
        <template v-if="!isCalibrating">
          <pv-switch
            v-model="guidedCapture"
            label="Guided auto-capture"
            :switch-cols="8"
            tooltip="Automatically save sharp, stable views that add observation coverage or diversity."
          />
          <p class="mb-3">
            Print at 100% scale, measure the squares and markers, and mount the board on a flat, rigid backing.
          </p>
        </template>
        <v-alert v-if="actionError" class="mb-3" color="error" variant="tonal" role="alert">{{ actionError }}</v-alert>
        <v-alert v-if="session?.solving || actionBusy" class="mb-3" color="info" variant="tonal">
          {{
            session?.solving
              ? "Solving calibration. Your saved calibration remains active until you apply this result."
              : "Updating calibration…"
          }}
        </v-alert>
        <div>
          <v-btn
            color="buttonPassive"
            size="small"
            block
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            :disabled="!settingsValid || boardType === CalibrationBoardTypes.Chessboard"
            @click="downloadCalibBoard"
          >
            <v-icon start class="calib-btn-icon" size="large"> mdi-download </v-icon>
            <span class="calib-btn-label">Generate Board</span>
          </v-btn>
        </div>
        <v-alert
          v-if="tooManyPoints"
          class="mt-5"
          color="error"
          density="compact"
          text="Too many corners. Finish calibration now!"
          icon="mdi-alert-circle-outline"
          :variant="theme.global.current.value.dark ? 'tonal' : 'elevated'"
        />
        <div class="d-flex pt-5">
          <v-col cols="6" class="pa-0 pr-2">
            <v-btn
              size="small"
              block
              color="buttonActive"
              :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
              :disabled="
                !settingsValid ||
                tooManyPoints ||
                actionBusy ||
                session?.solving ||
                (isCalibrating && (!session?.active || session.storageLimited))
              "
              @click="isCalibrating ? sessionAction('snapshot') : startCalibration()"
            >
              <v-icon start class="calib-btn-icon" size="large">
                {{ isCalibrating ? "mdi-camera" : "mdi-flag-outline" }}
              </v-icon>
              <span class="calib-btn-label">{{ isCalibrating ? "Take Snapshot" : "Start Calibration" }}</span>
            </v-btn>
          </v-col>
          <v-col cols="6" class="pa-0 pl-2">
            <v-btn
              size="small"
              block
              :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
              color="buttonActive"
              :disabled="!isCalibrating || !settingsValid || !canTryCalibration || actionBusy || session?.solving"
              @click="sessionAction('solve')"
            >
              <v-icon start class="calib-btn-icon" size="large"> mdi-flag-checkered </v-icon>
              <span class="calib-btn-label">Try Calibration</span>
            </v-btn>
          </v-col>
        </div>
        <div v-if="isCalibrating" class="d-flex ga-3 mt-3">
          <v-btn
            color="error"
            :disabled="(actionBusy && !session?.solving) || !session?.active"
            @click="sessionAction('cancel')"
            >Cancel Calibration</v-btn
          >
          <v-btn v-if="session?.pendingResult" @click="showReview = true">Review Result</v-btn>
        </div>
      </v-card-text>
    </v-card>
    <v-dialog v-model="showSnapshots" width="900px">
      <v-card>
        <v-card-title>Snapshot {{ snapshotIndex + 1 }} of {{ snapshotCount }}</v-card-title>
        <v-card-text>
          <v-img v-if="snapshotCount" :src="snapshotUrl" alt="Captured calibration board" max-height="550" />
          <p v-else>No snapshots remain. Close this view and collect more observations.</p>
          <p>Auto-capture is paused while reviewing. Resume it when ready to collect more views.</p>
          <v-alert v-if="actionError" color="error">{{ actionError }}</v-alert>
        </v-card-text>
        <v-card-actions>
          <v-btn :disabled="snapshotIndex === 0 || actionBusy" @click="snapshotIndex--">Previous</v-btn>
          <v-btn :disabled="snapshotIndex >= snapshotCount - 1 || actionBusy" @click="snapshotIndex++">Next</v-btn>
          <v-btn color="error" :disabled="!snapshotCount || actionBusy" @click="sessionAction('remove')"
            >Remove Snapshot</v-btn
          >
          <v-spacer />
          <v-btn @click="showSnapshots = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog v-model="showReview" width="80em">
      <v-card v-if="session?.pendingResult && sessionVideoFormat">
        <v-card-title>Review Calibration Result</v-card-title>
        <v-card-text
          >Check reprojection errors, observed corners, and projection uncertainty. Low error does not detect incorrect
          board dimensions or a warped board.</v-card-text
        >
        <CameraCalibrationInfoCard
          :video-format="sessionVideoFormat"
          :calibration="session.pendingResult"
          :session-id="session.sessionId"
        />
        <v-alert v-if="actionError" color="error">{{ actionError }}</v-alert>
        <v-card-actions>
          <v-btn
            :disabled="actionBusy"
            @click="
              sessionAction('resume');
              showReview = false;
            "
            >Collect More Views</v-btn
          >
          <v-spacer />
          <v-btn color="buttonActive" :disabled="actionBusy" @click="sessionAction('apply')">Apply Calibration</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog v-model="showCalDialog" width="80em">
      <CameraCalibrationInfoCard v-if="selectedVideoFormat" :video-format="selectedVideoFormat" />
    </v-dialog>
  </div>
</template>

<style scoped lang="scss">
.calibration-instructions {
  display: grid;
  gap: 0.5em;
  line-height: 1.5;
}

.calibration-guidance,
.calibration-feedback {
  display: block;
  overflow: auto;
  overflow-wrap: anywhere;
}

.calibration-guidance {
  height: 3em;
}

.calibration-feedback {
  height: 4.5em;
}

th {
  text-align: center !important;
  padding: 0 8px !important;
}

.v-table {
  text-align: center;
  width: 100%;

  th,
  td {
    font-size: 1rem !important;
  }

  tbody :hover td {
    cursor: pointer;
  }

  ::-webkit-scrollbar {
    width: 0;
    height: 0.55em;
    border-radius: 5px;
  }

  ::-webkit-scrollbar-track {
    -webkit-box-shadow: inset 0 0 6px rgba(0, 0, 0, 0.3);
    border-radius: 10px;
  }

  ::-webkit-scrollbar-thumb {
    background-color: rgb(var(--v-theme-accent));
    border-radius: 10px;
  }
}

@media only screen and (max-width: 512px) {
  .calib-btn-icon {
    margin: 0 !important;
  }
  .calib-btn-label {
    display: none;
  }
}
</style>
