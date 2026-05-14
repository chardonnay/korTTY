package de.kortty.ui;

import de.kortty.core.TerminalRecordingReplayFrame;
import de.kortty.core.TerminalRecordingReplayTimeline;
import de.kortty.core.TerminalRecordingTimeJumpParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

public class TerminalRecordingReplayDialog extends ThemeAwareDialog<Void> {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final TerminalRecordingReplayTimeline timeline;
    private final TextArea screenArea = new TextArea();
    private final Label statusLabel = new Label();
    private final Label timeLabel = new Label();
    private final Slider timelineSlider = new Slider();
    private final TextField timeJumpField = new TextField();
    private final Spinner<Integer> playbackSpeedSpinner = new Spinner<>(1, 20, 1);
    private final Button playPauseButton = new Button(I18n.get("recording.viewer.play"));
    private final Button stopButton = new Button(I18n.get("recording.viewer.stop"));
    private final Button seekButton = new Button(I18n.get("recording.viewer.seek"));
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            updatePlayback(now);
        }
    };
    private int frameIndex = -1;
    private long playbackStartedAtNanos;
    private double playbackStartPositionSeconds;
    private double currentPositionSeconds;
    private boolean playing;
    private boolean updatingTimeline;
    private boolean sliderSeeking;
    private boolean resumeAfterSliderSeek;

    public TerminalRecordingReplayDialog(Path replayFile, List<TerminalRecordingReplayFrame> frames) {
        List<TerminalRecordingReplayFrame> replayFrames = frames != null ? List.copyOf(frames) : List.of();
        this.timeline = new TerminalRecordingReplayTimeline(replayFrames);

        setTitle(I18n.get("recording.viewer.title"));
        setHeaderText(I18n.get("recording.viewer.header", replayFile != null ? replayFile.getFileName() : ""));
        setResizable(true);
        buildUi();
        setPlaybackPosition(0.0, true);
        setOnHidden(event -> timer.stop());
    }

    private void buildUi() {
        screenArea.setEditable(false);
        screenArea.setWrapText(false);
        screenArea.setFont(Font.font("Monospaced", 13));
        screenArea.setPrefColumnCount(120);
        screenArea.setPrefRowCount(34);

        playPauseButton.setDisable(timeline.isEmpty());
        playPauseButton.setOnAction(event -> togglePlayback());
        stopButton.setDisable(timeline.isEmpty());
        stopButton.setOnAction(event -> stopPlayback(true));

        timelineSlider.setMin(0.0);
        timelineSlider.setMax(timeline.isEmpty() ? 1.0 : timeline.totalDurationSeconds());
        timelineSlider.setBlockIncrement(timelineBlockIncrement());
        timelineSlider.setDisable(timeline.isEmpty());
        timelineSlider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (changing) {
                beginSliderSeek();
            } else if (sliderSeeking) {
                finishSliderSeek();
            }
        });
        timelineSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (updatingTimeline || timeline.isEmpty()) {
                return;
            }
            if (sliderSeeking) {
                setPlaybackPosition(newValue.doubleValue(), false);
            } else {
                seekToSeconds(newValue.doubleValue(), playing);
            }
        });

        timeJumpField.setPrefColumnCount(9);
        timeJumpField.setPromptText("MM:SS");
        timeJumpField.setDisable(timeline.isEmpty());
        timeJumpField.setOnAction(event -> seekToTimeJumpInput());
        seekButton.setDisable(timeline.isEmpty());
        seekButton.setOnAction(event -> seekToTimeJumpInput());

        playbackSpeedSpinner.setEditable(false);
        playbackSpeedSpinner.setPrefWidth(72);
        playbackSpeedSpinner.setDisable(timeline.isEmpty());
        playbackSpeedSpinner.valueProperty().addListener((obs, oldValue, newValue) -> restartPlaybackClockIfPlaying());

        HBox timelineControls = new HBox(
            8,
            new Label(I18n.get("recording.viewer.timeline")),
            timelineSlider,
            timeLabel);
        HBox.setHgrow(timelineSlider, Priority.ALWAYS);
        HBox controls = new HBox(
            8,
            playPauseButton,
            stopButton,
            new Label(I18n.get("recording.viewer.speed")),
            playbackSpeedSpinner,
            new Label(I18n.get("recording.viewer.speedSuffix")),
            statusLabel);
        HBox seekControls = new HBox(
            8,
            new Label(I18n.get("recording.viewer.timeJump")),
            timeJumpField,
            seekButton);
        VBox content = new VBox(8, screenArea, timelineControls, controls, seekControls);
        content.setPadding(new Insets(8));
        VBox.setVgrow(screenArea, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(1040);
        getDialogPane().setPrefHeight(720);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    private void togglePlayback() {
        if (playing) {
            pausePlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        if (timeline.isEmpty()) {
            return;
        }
        if (currentPositionSeconds >= timeline.totalDurationSeconds()) {
            setPlaybackPosition(0.0, true);
        }
        playing = true;
        restartPlaybackClock();
        playPauseButton.setText(I18n.get("recording.viewer.pause"));
        timer.start();
    }

    private void pausePlayback() {
        playing = false;
        timer.stop();
        playPauseButton.setText(I18n.get("recording.viewer.play"));
    }

    private void stopPlayback(boolean reset) {
        playing = false;
        timer.stop();
        playPauseButton.setText(I18n.get("recording.viewer.play"));
        if (reset) {
            setPlaybackPosition(0.0, true);
        }
    }

    private void updatePlayback(long now) {
        if (!playing || timeline.isEmpty()) {
            return;
        }
        double elapsedSeconds = ((now - playbackStartedAtNanos) / (double) NANOS_PER_SECOND)
            * playbackSpeedMultiplier();
        double targetSeconds = playbackStartPositionSeconds + elapsedSeconds;
        if (targetSeconds >= timeline.totalDurationSeconds()) {
            stopPlayback(false);
            setPlaybackPosition(timeline.totalDurationSeconds(), true);
            return;
        }
        setPlaybackPosition(targetSeconds, false);
    }

    private void beginSliderSeek() {
        sliderSeeking = true;
        resumeAfterSliderSeek = playing;
        if (playing) {
            pausePlayback();
        }
    }

    private void finishSliderSeek() {
        boolean resumePlayback = resumeAfterSliderSeek;
        sliderSeeking = false;
        resumeAfterSliderSeek = false;
        seekToSeconds(timelineSlider.getValue(), resumePlayback);
    }

    private void seekToTimeJumpInput() {
        if (timeline.isEmpty()) {
            return;
        }
        OptionalDouble seconds = TerminalRecordingTimeJumpParser.parseSeconds(
            timeJumpField.getText(),
            timeline.totalDurationSeconds());
        if (seconds.isEmpty()) {
            statusLabel.setText(I18n.get(
                "recording.viewer.seek.invalid",
                formatDuration(timeline.totalDurationSeconds())));
            return;
        }
        seekToSeconds(seconds.getAsDouble(), playing);
    }

    private void seekToSeconds(double seconds, boolean resumePlayback) {
        boolean shouldResume = resumePlayback && !timeline.isEmpty();
        playing = false;
        timer.stop();
        playPauseButton.setText(I18n.get("recording.viewer.play"));
        setPlaybackPosition(seconds, true);
        if (shouldResume && currentPositionSeconds < timeline.totalDurationSeconds()) {
            startPlayback();
        }
    }

    private void setPlaybackPosition(double seconds, boolean forceFrameUpdate) {
        if (timeline.isEmpty()) {
            screenArea.setText(I18n.get("recording.viewer.empty"));
            currentPositionSeconds = 0.0;
            frameIndex = 0;
            updatePlaybackIndicators();
            return;
        }

        double clampedSeconds = timeline.clampSeconds(seconds);
        int nextFrameIndex = timeline.frameIndexAt(clampedSeconds);
        boolean frameChanged = forceFrameUpdate || nextFrameIndex != frameIndex;
        currentPositionSeconds = clampedSeconds;
        frameIndex = nextFrameIndex;
        if (frameChanged) {
            screenArea.setText(timeline.frame(frameIndex).content());
            screenArea.positionCaret(0);
        }
        updatePlaybackIndicators();
    }

    private void updatePlaybackIndicators() {
        int visibleFrame = timeline.isEmpty() ? 0 : Math.min(frameIndex + 1, timeline.frameCount());
        statusLabel.setText(I18n.get("recording.viewer.status", visibleFrame, timeline.frameCount()));
        timeLabel.setText(I18n.get(
            "recording.viewer.time",
            formatDuration(currentPositionSeconds),
            formatDuration(timeline.totalDurationSeconds())));
        if (!sliderSeeking) {
            updatingTimeline = true;
            try {
                timelineSlider.setValue(timeline.clampSeconds(currentPositionSeconds));
            } finally {
                updatingTimeline = false;
            }
        }
    }

    private double timelineBlockIncrement() {
        double totalSeconds = timeline.totalDurationSeconds();
        if (totalSeconds <= 0.0) {
            return 1.0;
        }
        return Math.max(1.0, Math.min(30.0, totalSeconds / 20.0));
    }

    private void restartPlaybackClockIfPlaying() {
        if (playing) {
            restartPlaybackClock();
        }
    }

    private void restartPlaybackClock() {
        playbackStartPositionSeconds = currentPositionSeconds;
        playbackStartedAtNanos = System.nanoTime();
    }

    private int playbackSpeedMultiplier() {
        Integer value = playbackSpeedSpinner.getValue();
        if (value == null) {
            return 1;
        }
        return Math.max(1, Math.min(20, value));
    }

    private static String formatDuration(double seconds) {
        long roundedSeconds = Math.max(0L, Math.round(seconds));
        long hours = roundedSeconds / 3600L;
        long minutes = (roundedSeconds % 3600L) / 60L;
        long remainingSeconds = roundedSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
    }

}
