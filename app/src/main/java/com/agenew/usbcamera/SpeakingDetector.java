package com.agenew.usbcamera;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.RectF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import org.tensorflow.lite.Interpreter;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;

final class SpeakingDetector implements Closeable {
    private static final String FACE_MODEL = "face_landmarker.task";
    private static final String SPEAKING_MODEL = "lip_motion_net.tflite";
    private static final int WINDOW_SIZE = 25;
    private static final int FAST_WINDOW_SIZE = 5;
    private static final int MAX_CONSECUTIVE_MISSING_FACES = 3;
    private static final float SPEAKING_START_THRESHOLD = 0.55f;
    private static final float SPEAKING_CONTINUE_THRESHOLD = 0.48f;
    private static final float MIN_MOUTH_MOTION_START_RATIO = 0.006f;
    private static final float MIN_MOUTH_MOTION_CONTINUE_RATIO = 0.0045f;
    private static final float FAST_MOUTH_MOTION_START_RATIO = 0.016f;
    private static final float FAST_MOUTH_DELTA_START_RATIO = 0.010f;
    private static final float FAST_MOUTH_MOTION_CONTINUE_RATIO = 0.010f;
    private static final float STRONG_MOUTH_MOTION_RATIO = 0.055f;
    private static final float STRONG_MOTION_MIN_SPEAKING_PROB = 0.35f;
    private static final int START_CONFIRM_FRAMES = 2;
    private static final int STOP_CONFIRM_FRAMES = 8;
    private static final int[] MOUTH_DEBUG_POINTS = {82, 87, 13, 14, 312, 317};

    private final FaceLandmarker faceLandmarker;
    private final Interpreter interpreter;
    private final float[] gapHistory = new float[WINDOW_SIZE];
    private int historyCount = 0;
    private int historyIndex = 0;
    private int consecutiveMissingFaces = 0;
    private long lastTimestampMs = 0L;
    private int speakingCandidateCount = 0;
    private int silentCandidateCount = 0;
    private boolean smoothedSpeaking = false;

    SpeakingDetector(Context context) throws IOException {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(FACE_MODEL)
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build();
        faceLandmarker = FaceLandmarker.createFromOptions(context, options);
        interpreter = new Interpreter(loadModelFile(context, SPEAKING_MODEL), new Interpreter.Options().setNumThreads(2));
    }

    Detection detect(Bitmap bitmap) {
        Bitmap inputBitmap = bitmap;
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        MPImage image = new BitmapImageBuilder(inputBitmap).build();
        long timestampMs = SystemClock.uptimeMillis();
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1L;
        }
        lastTimestampMs = timestampMs;

        FaceLandmarkerResult result = faceLandmarker.detectForVideo(image, timestampMs);
        if (result.faceLandmarks().isEmpty()) {
            consecutiveMissingFaces++;
            if (consecutiveMissingFaces >= MAX_CONSECUTIVE_MISSING_FACES) {
                resetHistory();
            }
            return Detection.noFace(historyCount, WINDOW_SIZE);
        }
        consecutiveMissingFaces = 0;

        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);
        RectF faceBox = computeFaceBox(landmarks);
        float mouthGap = computeMouthGap(landmarks, bitmap.getWidth(), bitmap.getHeight());
        float[] mouthPoints = collectMouthPoints(landmarks);
        addGap(mouthGap);

        float faceHeightPx = Math.max(1.0f, (faceBox.bottom - faceBox.top) * bitmap.getHeight());
        float fastMotionRange = computeRecentMotionRange(FAST_WINDOW_SIZE);
        float fastMotionRatio = fastMotionRange / faceHeightPx;
        float fastGapDeltaRatio = computeLatestGapDelta() / faceHeightPx;
        boolean fastRawSpeaking = shouldFastTriggerSpeaking(fastMotionRatio, fastGapDeltaRatio);

        if (historyCount < WINDOW_SIZE) {
            boolean speaking = updateSpeakingState(fastRawSpeaking);
            return Detection.processing(faceBox, mouthPoints, mouthGap, fastMotionRange, fastMotionRatio,
                    speaking, fastRawSpeaking, historyCount, WINDOW_SIZE);
        }

        float[][][] input = new float[1][WINDOW_SIZE][1];
        float mouthMotionRange = fillNormalizedWindow(input);
        float mouthMotionRatio = mouthMotionRange / faceHeightPx;

        float[][] output = new float[1][2];
        interpreter.run(input, output);

        float silentProb = output[0][0];
        float speakingProb = output[0][1];
        boolean rawSpeaking = fastRawSpeaking || shouldTriggerSpeaking(speakingProb, mouthMotionRatio);
        boolean speaking = updateSpeakingState(rawSpeaking);
        return Detection.finished(faceBox, mouthPoints, mouthGap, mouthMotionRange, mouthMotionRatio,
                silentProb, speakingProb, speaking, rawSpeaking, historyCount, WINDOW_SIZE);
    }

    private static MappedByteBuffer loadModelFile(Context context, String assetName) throws IOException {
        AssetFileDescriptor descriptor = context.getAssets().openFd(assetName);
        FileInputStream inputStream = new FileInputStream(descriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getDeclaredLength());
    }

    private void addGap(float gap) {
        gapHistory[historyIndex] = gap;
        historyIndex = (historyIndex + 1) % WINDOW_SIZE;
        if (historyCount < WINDOW_SIZE) {
            historyCount++;
        }
    }

    private void resetHistory() {
        Arrays.fill(gapHistory, 0f);
        historyCount = 0;
        historyIndex = 0;
        speakingCandidateCount = 0;
        silentCandidateCount = 0;
        smoothedSpeaking = false;
    }

    private boolean shouldTriggerSpeaking(float speakingProb, float mouthMotionRatio) {
        float modelThreshold = smoothedSpeaking ? SPEAKING_CONTINUE_THRESHOLD : SPEAKING_START_THRESHOLD;
        float motionThreshold = smoothedSpeaking ? MIN_MOUTH_MOTION_CONTINUE_RATIO : MIN_MOUTH_MOTION_START_RATIO;
        boolean modelTrigger = speakingProb >= modelThreshold && mouthMotionRatio >= motionThreshold;
        boolean strongMotionTrigger = mouthMotionRatio >= STRONG_MOUTH_MOTION_RATIO
                && speakingProb >= STRONG_MOTION_MIN_SPEAKING_PROB;
        return modelTrigger || strongMotionTrigger;
    }

    private boolean shouldFastTriggerSpeaking(float fastMotionRatio, float fastGapDeltaRatio) {
        if (historyCount < 2) {
            return false;
        }
        if (smoothedSpeaking) {
            return fastMotionRatio >= FAST_MOUTH_MOTION_CONTINUE_RATIO;
        }
        return fastMotionRatio >= FAST_MOUTH_MOTION_START_RATIO
                || fastGapDeltaRatio >= FAST_MOUTH_DELTA_START_RATIO;
    }

    private boolean updateSpeakingState(boolean rawSpeaking) {
        if (rawSpeaking) {
            speakingCandidateCount++;
            silentCandidateCount = 0;
            if (!smoothedSpeaking && speakingCandidateCount >= START_CONFIRM_FRAMES) {
                smoothedSpeaking = true;
            }
        } else {
            silentCandidateCount++;
            speakingCandidateCount = 0;
            if (smoothedSpeaking && silentCandidateCount >= STOP_CONFIRM_FRAMES) {
                smoothedSpeaking = false;
            }
        }
        return smoothedSpeaking;
    }

    private float fillNormalizedWindow(float[][][] input) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float[] ordered = new float[WINDOW_SIZE];
        for (int i = 0; i < WINDOW_SIZE; i++) {
            int idx = (historyIndex + i) % WINDOW_SIZE;
            float value = gapHistory[idx];
            ordered[i] = value;
            if (value < min) {
                min = value;
            }
            if (value > max) {
                max = value;
            }
        }

        float range = max - min;
        for (int i = 0; i < WINDOW_SIZE; i++) {
            input[0][i][0] = range > 1e-6f ? (ordered[i] - min) / range : 0f;
        }
        return range;
    }

    private float computeRecentMotionRange(int maxFrames) {
        int count = Math.min(historyCount, maxFrames);
        if (count < 2) {
            return 0f;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int idx = (historyIndex - 1 - i + WINDOW_SIZE) % WINDOW_SIZE;
            float value = gapHistory[idx];
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private float computeLatestGapDelta() {
        if (historyCount < 2) {
            return 0f;
        }
        int currentIdx = (historyIndex - 1 + WINDOW_SIZE) % WINDOW_SIZE;
        int previousIdx = (historyIndex - 2 + WINDOW_SIZE) % WINDOW_SIZE;
        return Math.abs(gapHistory[currentIdx] - gapHistory[previousIdx]);
    }

    private static float computeMouthGap(List<NormalizedLandmark> landmarks, int width, int height) {
        float leftGap = distance(landmarks.get(82), landmarks.get(87), width, height);
        float centerGap = distance(landmarks.get(13), landmarks.get(14), width, height);
        float rightGap = distance(landmarks.get(312), landmarks.get(317), width, height);
        return (leftGap + centerGap + rightGap) / 3.0f;
    }

    private static float distance(NormalizedLandmark a, NormalizedLandmark b, int width, int height) {
        float dx = (a.x() - b.x()) * width;
        float dy = (a.y() - b.y()) * height;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float[] collectMouthPoints(List<NormalizedLandmark> landmarks) {
        float[] points = new float[MOUTH_DEBUG_POINTS.length * 2];
        for (int i = 0; i < MOUTH_DEBUG_POINTS.length; i++) {
            NormalizedLandmark landmark = landmarks.get(MOUTH_DEBUG_POINTS[i]);
            points[i * 2] = landmark.x();
            points[i * 2 + 1] = landmark.y();
        }
        return points;
    }

    private static RectF computeFaceBox(List<NormalizedLandmark> landmarks) {
        float left = 1.0f;
        float top = 1.0f;
        float right = 0.0f;
        float bottom = 0.0f;
        for (NormalizedLandmark landmark : landmarks) {
            left = Math.min(left, landmark.x());
            top = Math.min(top, landmark.y());
            right = Math.max(right, landmark.x());
            bottom = Math.max(bottom, landmark.y());
        }
        return new RectF(left, top, right, bottom);
    }

    @Override
    public void close() {
        faceLandmarker.close();
        interpreter.close();
    }

    static final class Detection {
        final boolean hasFace;
        final boolean ready;
        final RectF faceBox;
        final float[] mouthPoints;
        final float mouthGap;
        final float mouthMotionRange;
        final float mouthMotionRatio;
        final float silentProb;
        final float speakingProb;
        final boolean speaking;
        final boolean rawSpeaking;
        final int historyCount;
        final int windowSize;

        private Detection(boolean hasFace, boolean ready, RectF faceBox, float[] mouthPoints, float mouthGap,
                          float mouthMotionRange, float mouthMotionRatio,
                          float silentProb, float speakingProb, boolean speaking, boolean rawSpeaking,
                          int historyCount, int windowSize) {
            this.hasFace = hasFace;
            this.ready = ready;
            this.faceBox = faceBox;
            this.mouthPoints = mouthPoints;
            this.mouthGap = mouthGap;
            this.mouthMotionRange = mouthMotionRange;
            this.mouthMotionRatio = mouthMotionRatio;
            this.silentProb = silentProb;
            this.speakingProb = speakingProb;
            this.speaking = speaking;
            this.rawSpeaking = rawSpeaking;
            this.historyCount = historyCount;
            this.windowSize = windowSize;
        }

        static Detection noFace(int historyCount, int windowSize) {
            return new Detection(false, false, null, null, 0f, 0f, 0f, 0f, 0f,
                    false, false, historyCount, windowSize);
        }

        static Detection processing(RectF faceBox, float[] mouthPoints, float mouthGap,
                                    float mouthMotionRange, float mouthMotionRatio,
                                    boolean speaking, boolean rawSpeaking,
                                    int historyCount, int windowSize) {
            return new Detection(true, false, faceBox, mouthPoints, mouthGap,
                    mouthMotionRange, mouthMotionRatio, 0f, 0f, speaking, rawSpeaking, historyCount, windowSize);
        }

        static Detection finished(RectF faceBox, float[] mouthPoints, float mouthGap,
                                  float mouthMotionRange, float mouthMotionRatio, float silentProb,
                                  float speakingProb, boolean speaking, boolean rawSpeaking,
                                  int historyCount, int windowSize) {
            return new Detection(true, true, faceBox, mouthPoints, mouthGap, mouthMotionRange,
                    mouthMotionRatio, silentProb, speakingProb, speaking, rawSpeaking, historyCount, windowSize);
        }

        boolean isSpeaking() {
            return speaking;
        }
    }
}
