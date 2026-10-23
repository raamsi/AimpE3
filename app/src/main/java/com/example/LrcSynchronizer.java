package com.example;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to parse standard .lrc file formats and synchronize timestamps
 * with Android MediaPlayer playback position in real time.
 */
public class LrcSynchronizer {

    public interface OnLyricSyncListener {
        /**
         * Called when the active lyric line changes based on playback position.
         *
         * @param lineIndex The zero-based index of the active lyric line (-1 if before first lyric).
         * @param activeLine The active lyric entry, or null if before first lyric.
         * @param nextLine The subsequent upcoming lyric entry, or null if at the last lyric.
         */
        void onLyricLineChanged(int lineIndex, LyricEntry activeLine, LyricEntry nextLine);

        /**
         * Called continuously during playback to report the progress of the active line.
         *
         * @param lineIndex The index of the active lyric line.
         * @param activeLine The active lyric entry.
         * @param progress Progress percentage between current line and next line [0.0f .. 1.0f].
         */
        void onLyricProgress(int lineIndex, LyricEntry activeLine, float progress);
    }

    private static final Pattern TIME_TAG_PATTERN =
        Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[\\.,](\\d{1,3}))?\\]");
    private static final Pattern METADATA_TAG_PATTERN =
        Pattern.compile("^\\[(ti|ar|al|by|offset|length):\\s*(.*)\\]$", Pattern.CASE_INSENSITIVE);

    public static class LyricEntry {
        public final int timeMs;
        public int endTimeMs;
        public final String text;

        public LyricEntry(int timeMs, String text) {
            this(timeMs, -1, text);
        }

        public LyricEntry(int timeMs, int endTimeMs, String text) {
            this.timeMs = Math.max(0, timeMs);
            this.endTimeMs = endTimeMs;
            this.text = text != null ? text : "";
        }

        public int getDurationMs() {
            return endTimeMs > timeMs ? (endTimeMs - timeMs) : 0;
        }

        public String getFormattedTime() {
            return formatTimestamp(timeMs);
        }

        @Override
        public String toString() {
            return "[" + getFormattedTime() + "] " + text;
        }
    }

    public static class LrcMetadata {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String creator = "";
        public int offsetMs = 0;
    }

    private final List<LyricEntry> entries = new ArrayList<LyricEntry>();
    private final LrcMetadata metadata = new LrcMetadata();

    private Handler syncHandler;
    private MediaPlayer boundPlayer;
    private OnLyricSyncListener syncListener;
    private int currentIntervalMs = 50;
    private int lastReportedIndex = -2;
    private boolean isSyncActive = false;

    private Handler getHandler() {
        if (syncHandler == null) {
            syncHandler = new Handler(Looper.getMainLooper());
        }
        return syncHandler;
    }

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSyncActive || boundPlayer == null) return;

            int currentPos = 0;
            try {
                if (boundPlayer.isPlaying()) {
                    currentPos = boundPlayer.getCurrentPosition();
                } else {
                    getHandler().postDelayed(this, currentIntervalMs * 2L);
                    return;
                }
            } catch (IllegalStateException e) {
                stopSync();
                return;
            }

            updatePlaybackPosition(currentPos);
            getHandler().postDelayed(this, currentIntervalMs);
        }
    };

    public LrcSynchronizer() {}

    public static LrcSynchronizer parse(String lrcText) {
        LrcSynchronizer synchronizer = new LrcSynchronizer();
        synchronizer.parseInternal(lrcText);
        return synchronizer;
    }

    public static LrcSynchronizer parse(File file) {
        if (file == null || !file.exists()) return new LrcSynchronizer();
        try (FileInputStream fis = new FileInputStream(file)) {
            return parse(fis);
        } catch (Exception e) {
            return new LrcSynchronizer();
        }
    }

    public static LrcSynchronizer parse(InputStream inputStream) {
        if (inputStream == null) return new LrcSynchronizer();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {}
        return parse(sb.toString());
    }

    public static LrcSynchronizer fromLegacyLines(List<LrcParser.LyricLine> legacyLines) {
        LrcSynchronizer synchronizer = new LrcSynchronizer();
        if (legacyLines != null) {
            for (LrcParser.LyricLine line : legacyLines) {
                if (line != null && line.text != null) {
                    synchronizer.entries.add(new LyricEntry(line.timeMs, line.text));
                }
            }
            synchronizer.finalizeEntries();
        }
        return synchronizer;
    }

    private void parseInternal(String rawText) {
        entries.clear();
        if (rawText == null || rawText.trim().isEmpty()) return;

        int globalOffset = 0;
        String[] lines = rawText.split("\\r?\\n");

        // Pass 1: Extract global offset tag if present
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String trimmed = rawLine.trim().replace("\uFEFF", "");
            Matcher metaMatcher = METADATA_TAG_PATTERN.matcher(trimmed);
            if (metaMatcher.matches()) {
                String tag = metaMatcher.group(1).toLowerCase(Locale.US);
                String value = metaMatcher.group(2) != null ? metaMatcher.group(2).trim() : "";
                if ("offset".equals(tag)) {
                    try {
                        globalOffset = Integer.parseInt(value);
                        metadata.offsetMs = globalOffset;
                    } catch (NumberFormatException ignored) {}
                } else if ("ti".equals(tag)) {
                    metadata.title = value;
                } else if ("ar".equals(tag)) {
                    metadata.artist = value;
                } else if ("al".equals(tag)) {
                    metadata.album = value;
                } else if ("by".equals(tag)) {
                    metadata.creator = value;
                }
            }
        }

        // Pass 2: Extract timestamped lyric lines
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String trimmed = rawLine.trim().replace("\uFEFF", "");
            if (trimmed.isEmpty() || METADATA_TAG_PATTERN.matcher(trimmed).matches()) continue;

            Matcher matcher = TIME_TAG_PATTERN.matcher(trimmed);
            List<Integer> timestamps = new ArrayList<Integer>();

            while (matcher.find()) {
                try {
                    long minutes = Long.parseLong(matcher.group(1));
                    long seconds = Long.parseLong(matcher.group(2));
                    String fraction = matcher.group(3);
                    int fractionMs = 0;
                    if (fraction != null) {
                        if (fraction.length() == 1) fractionMs = Integer.parseInt(fraction) * 100;
                        else if (fraction.length() == 2) fractionMs = Integer.parseInt(fraction) * 10;
                        else fractionMs = Integer.parseInt(fraction.substring(0, Math.min(3, fraction.length())));
                    }
                    long totalMs = minutes * 60000L + seconds * 1000L + fractionMs + globalOffset;
                    timestamps.add((int) Math.max(0L, Math.min(Integer.MAX_VALUE, totalMs)));
                } catch (NumberFormatException ignored) {}
            }

            String cleanText = TIME_TAG_PATTERN.matcher(trimmed).replaceAll("").trim();
            if (!timestamps.isEmpty() && !cleanText.isEmpty()) {
                for (int time : timestamps) {
                    entries.add(new LyricEntry(time, cleanText));
                }
            }
        }

        finalizeEntries();
    }

    private void finalizeEntries() {
        Collections.sort(entries, new Comparator<LyricEntry>() {
            @Override
            public int compare(LyricEntry o1, LyricEntry o2) {
                return Integer.compare(o1.timeMs, o2.timeMs);
            }
        });

        // Compute endTimeMs for each line based on the next entry
        int count = entries.size();
        for (int i = 0; i < count; i++) {
            LyricEntry current = entries.get(i);
            if (i < count - 1) {
                current.endTimeMs = entries.get(i + 1).timeMs;
            } else {
                current.endTimeMs = current.timeMs + 6000; // Default 6 seconds for last line
            }
        }
    }

    // =========================================================================
    //  Binary Search Synchronization O(log N)
    // =========================================================================

    /**
     * Finds the index of the currently active lyric line for the given timestamp.
     *
     * @param currentPositionMs Current playback position in milliseconds.
     * @return Zero-based index of the line, or -1 if position is before the first line.
     */
    public int findActiveIndex(int currentPositionMs) {
        if (entries.isEmpty()) return -1;
        if (currentPositionMs < entries.get(0).timeMs) return -1;

        int lo = 0;
        int hi = entries.size() - 1;
        int result = -1;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).timeMs <= currentPositionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    public LyricEntry getActiveLine(int currentPositionMs) {
        int index = findActiveIndex(currentPositionMs);
        return (index >= 0 && index < entries.size()) ? entries.get(index) : null;
    }

    public LyricEntry getNextLine(int currentPositionMs) {
        int index = findActiveIndex(currentPositionMs);
        int nextIndex = index + 1;
        return (nextIndex >= 0 && nextIndex < entries.size()) ? entries.get(nextIndex) : null;
    }

    /**
     * Calculates completion ratio of current line before next line begins [0.0f .. 1.0f].
     */
    public float getLineProgress(int currentPositionMs) {
        int index = findActiveIndex(currentPositionMs);
        if (index < 0 || index >= entries.size()) return 0f;

        LyricEntry current = entries.get(index);
        int start = current.timeMs;
        int end = current.endTimeMs;
        if (end <= start) return 1.0f;

        float ratio = (float) (currentPositionMs - start) / (float) (end - start);
        return Math.max(0.0f, Math.min(1.0f, ratio));
    }

    // =========================================================================
    //  MediaPlayer Real-Time Synchronization Pipeline
    // =========================================================================

    public void startSync(MediaPlayer mediaPlayer, OnLyricSyncListener listener) {
        startSync(mediaPlayer, 50, listener);
    }

    public void startSync(MediaPlayer mediaPlayer, int pollIntervalMs, OnLyricSyncListener listener) {
        stopSync();
        this.boundPlayer = mediaPlayer;
        this.syncListener = listener;
        this.currentIntervalMs = Math.max(16, pollIntervalMs);
        this.lastReportedIndex = -2;
        this.isSyncActive = true;

        getHandler().post(syncRunnable);
    }

    public void stopSync() {
        isSyncActive = false;
        if (syncHandler != null) {
            syncHandler.removeCallbacks(syncRunnable);
        }
        boundPlayer = null;
    }

    public boolean isSyncing() {
        return isSyncActive;
    }

    /**
     * Updates the playback position manually or via timer, triggering callbacks if line changed.
     */
    public void updatePlaybackPosition(int currentPositionMs) {
        int activeIdx = findActiveIndex(currentPositionMs);
        LyricEntry activeLine = (activeIdx >= 0 && activeIdx < entries.size()) ? entries.get(activeIdx) : null;
        LyricEntry nextLine = (activeIdx + 1 >= 0 && activeIdx + 1 < entries.size()) ? entries.get(activeIdx + 1) : null;

        if (activeIdx != lastReportedIndex) {
            lastReportedIndex = activeIdx;
            if (syncListener != null) {
                syncListener.onLyricLineChanged(activeIdx, activeLine, nextLine);
            }
        }

        if (syncListener != null && activeLine != null) {
            float progress = getLineProgress(currentPositionMs);
            syncListener.onLyricProgress(activeIdx, activeLine, progress);
        }
    }

    /**
     * Seeks the MediaPlayer directly to the start timestamp of the selected lyric line.
     */
    public void seekToLine(MediaPlayer player, int lineIndex) {
        if (player == null || lineIndex < 0 || lineIndex >= entries.size()) return;
        int targetMs = entries.get(lineIndex).timeMs;
        try {
            player.seekTo(targetMs);
            updatePlaybackPosition(targetMs);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    //  Data Access & Formatting
    // =========================================================================

    public List<LyricEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int getLineCount() {
        return entries.size();
    }

    public LyricEntry getLine(int index) {
        return (index >= 0 && index < entries.size()) ? entries.get(index) : null;
    }

    public LrcMetadata getMetadata() {
        return metadata;
    }

    public ArrayList<LrcParser.LyricLine> toLegacyLines() {
        ArrayList<LrcParser.LyricLine> list = new ArrayList<LrcParser.LyricLine>(entries.size());
        for (LyricEntry e : entries) {
            list.add(new LrcParser.LyricLine(e.timeMs, e.text));
        }
        return list;
    }

    public String toLrcString() {
        StringBuilder sb = new StringBuilder();
        if (metadata.title != null && !metadata.title.isEmpty()) {
            sb.append("[ti:").append(metadata.title).append("]\n");
        }
        if (metadata.artist != null && !metadata.artist.isEmpty()) {
            sb.append("[ar:").append(metadata.artist).append("]\n");
        }
        if (metadata.album != null && !metadata.album.isEmpty()) {
            sb.append("[al:").append(metadata.album).append("]\n");
        }
        if (metadata.offsetMs != 0) {
            sb.append("[offset:").append(metadata.offsetMs).append("]\n");
        }

        for (LyricEntry entry : entries) {
            sb.append(entry.toString()).append('\n');
        }
        return sb.toString();
    }

    public static String formatTimestamp(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        int hundredths = (ms % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, hundredths);
    }
}
