package com.example;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcParser {
    private static final Pattern TIME_TAG_PATTERN =
        Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[\\.,](\\d{1,3}))?\\]");
    private static final Pattern OFFSET_PATTERN =
        Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_PATTERN =
        Pattern.compile("^\\[(?:ti|ar|al|re|ve|by|length):.*\\]$", Pattern.CASE_INSENSITIVE);

    public static class LyricLine {
        public final int timeMs;
        public final String text;

        public LyricLine(int timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static ArrayList<LyricLine> parseLrcFile(Context context, String audioPath) {
        ArrayList<LyricLine> lyricsList = new ArrayList<LyricLine>();
        if (audioPath == null || audioPath.isEmpty()) {
            return lyricsList;
        }

        try {
            InputStream inputStream = null;

            if (!audioPath.startsWith("content://")) {
                int extension = audioPath.lastIndexOf('.');
                if (extension <= audioPath.lastIndexOf(File.separator)) return lyricsList;
                String lrcPath = audioPath.substring(0, extension) + ".lrc";
                File lrcFile = new File(lrcPath);

                if (!lrcFile.exists()) {
                    lrcFile = findLrcCaseInsensitive(audioPath);
                }

                if (lrcFile != null && lrcFile.exists()) {
                    inputStream = new FileInputStream(lrcFile);
                }
            } else {
                String realPath = getRealPathFromURI(context, Uri.parse(audioPath));
                if (realPath != null && realPath.contains(".")) {
                    String lrcPath = realPath.substring(0, realPath.lastIndexOf('.')) + ".lrc";
                    File lrcFile = new File(lrcPath);
                    if (!lrcFile.exists()) {
                        lrcFile = findLrcCaseInsensitive(realPath);
                    }
                    if (lrcFile != null && lrcFile.exists()) {
                        inputStream = new FileInputStream(lrcFile);
                    }
                }
            }

            if (inputStream == null) {
                return lyricsList;
            }

            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line).append('\n');
                }
            }
            return parseLrcString(raw.toString());

        } catch (Exception e) {
            e.printStackTrace();
        }

        return lyricsList;
    }

    public static ArrayList<LyricLine> parseLrcFileDirect(File lrcFile) {
        ArrayList<LyricLine> lyricsList = new ArrayList<LyricLine>();
        if (lrcFile == null || !lrcFile.exists()) {
            return lyricsList;
        }
        try {
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(new FileInputStream(lrcFile), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line).append('\n');
                }
            }
            return parseLrcString(raw.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lyricsList;
    }

    private static void parseLine(String line, ArrayList<LyricLine> list, int offsetMs) {
        if (line == null) return;
        line = line.trim().replace("\uFEFF", "");
        if (line.isEmpty() || METADATA_PATTERN.matcher(line).matches()
            || OFFSET_PATTERN.matcher(line).matches()) return;

        Matcher matcher = TIME_TAG_PATTERN.matcher(line);
        ArrayList<Integer> timestamps = new ArrayList<Integer>();
        while (matcher.find()) {
            try {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                int fractionMs = 0;
                if (fraction != null) {
                    if (fraction.length() == 1) fractionMs = Integer.parseInt(fraction) * 100;
                    else if (fraction.length() == 2) fractionMs = Integer.parseInt(fraction) * 10;
                    else fractionMs = Integer.parseInt(fraction);
                }
                long time = minutes * 60000L + seconds * 1000L + fractionMs + offsetMs;
                timestamps.add((int) Math.max(0L, Math.min(Integer.MAX_VALUE, time)));
            } catch (NumberFormatException ignored) {}
        }

        String cleanText = TIME_TAG_PATTERN.matcher(line).replaceAll("").trim();
        if (cleanText.isEmpty() || timestamps.isEmpty()) return;
        for (Integer timestamp : timestamps) {
            list.add(new LyricLine(timestamp, cleanText));
        }
    }

    private static String getRealPathFromURI(Context context, Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] proj = { MediaStore.Audio.Media.DATA };
            cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                if (columnIndex != -1) {
                    return cursor.getString(columnIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public static ArrayList<LyricLine> parseLrcString(String rawText) {
        ArrayList<LyricLine> lyricsList = new ArrayList<LyricLine>();
        if (rawText == null || rawText.isEmpty()) {
            return lyricsList;
        }
        try {
            String[] lines = rawText.split("\\r?\\n");
            int offsetMs = 0;
            for (String line : lines) {
                if (line == null) continue;
                Matcher offsetMatcher = OFFSET_PATTERN.matcher(line.trim());
                if (offsetMatcher.matches()) {
                    try {
                        offsetMs = Integer.parseInt(offsetMatcher.group(1));
                    } catch (NumberFormatException ignored) {}
                    continue;
                }
                parseLine(line, lyricsList, offsetMs);
            }
            Collections.sort(lyricsList, new Comparator<LyricLine>() {
                @Override
                public int compare(LyricLine l1, LyricLine l2) {
                    return Integer.compare(l1.timeMs, l2.timeMs);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lyricsList;
    }

    private static File findLrcCaseInsensitive(String audioPath) {
        try {
            File audioFile = new File(audioPath);
            File parentDir = audioFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                int lastDot = audioFile.getName().lastIndexOf('.');
                if (lastDot == -1) return null;
                String audioNameNoExt = audioFile.getName().substring(0, lastDot);

                File[] files = parentDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().toLowerCase().endsWith(".lrc")) {
                            int lrcLastDot = f.getName().lastIndexOf('.');
                            String lrcNameNoExt = f.getName().substring(0, lrcLastDot);
                            if (lrcNameNoExt.equalsIgnoreCase(audioNameNoExt)) {
                                return f;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
