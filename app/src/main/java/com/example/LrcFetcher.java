package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.os.Handler;
import android.os.Looper;

public class LrcFetcher {

    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Pattern SYNCED_LYRICS_PATTERN =
        Pattern.compile("\\[\\d{1,3}:\\d{2}(?:[\\.,]\\d{1,3})?\\]");

    public interface LyricCallback {
        void onLyricsFetched(String syncedLyrics);
    }

    public static void fetchLyricsOnline(final String title, final String artist, final int durationMs, final LyricCallback callback) {
        if (callback == null) return;
        NETWORK_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String syncedLyrics = null;
                try {
                    syncedLyrics = fetchDirect(title, artist, durationMs);
                    if (syncedLyrics == null || syncedLyrics.isEmpty()) {
                        syncedLyrics = fetchSearchFallback(title, artist, durationMs);
                    }
                } catch (RuntimeException ignored) {
                }
                final String result = syncedLyrics;
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onLyricsFetched(result);
                    }
                });
            }
        });
    }

    private static boolean isSyncedFormat(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        Matcher matcher = SYNCED_LYRICS_PATTERN.matcher(text);
        return matcher.find();
    }

    private static String fetchDirect(String title, String artist, int durationMs) {
        try {
            String queryTitle = URLEncoder.encode(title != null ? title : "", "UTF-8");
            String queryArtist = URLEncoder.encode(artist != null ? artist : "", "UTF-8");
            long durationSec = durationMs / 1000;

            String urlString = "https://lrclib.net/api/get?track_name=" + queryTitle + "&artist_name=" + queryArtist;
            if (durationSec > 0) {
                urlString += "&duration=" + durationSec;
            }

            String jsonResponse = makeHttpRequest(urlString);
            if (jsonResponse != null) {
                JSONObject json = new JSONObject(jsonResponse);
                if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) {
                    String synced = json.getString("syncedLyrics").trim();
                    if (isSyncedFormat(synced)) {
                        return synced;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String fetchSearchFallback(String title, String artist, int durationMs) {
        try {
            String rawQuery = ((title != null ? title : "") + " "
                               + (artist != null ? artist : "")).trim();
            if (rawQuery.isEmpty()) return null;
            String searchQuery = URLEncoder.encode(rawQuery, "UTF-8");

            String urlString = "https://lrclib.net/api/search?q=" + searchQuery;
            String jsonResponse = makeHttpRequest(urlString);

            if (jsonResponse != null) {
                JSONArray array = new JSONArray(jsonResponse);
                String wantedTitle = normalize(title);
                String wantedArtist = normalize(artist);
                long wantedDuration = Math.max(0L, durationMs / 1000L);
                String bestLyrics = null;
                int bestScore = Integer.MIN_VALUE;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    if (item.has("syncedLyrics") && !item.isNull("syncedLyrics")) {
                        String synced = item.getString("syncedLyrics").trim();
                        if (isSyncedFormat(synced)) {
                            int score = 0;
                            String foundTitle = normalize(item.optString("trackName", ""));
                            String foundArtist = normalize(item.optString("artistName", ""));
                            if (!wantedTitle.isEmpty()) {
                                if (wantedTitle.equals(foundTitle)) score += 5;
                                else if (!foundTitle.isEmpty()
                                         && (foundTitle.contains(wantedTitle)
                                             || wantedTitle.contains(foundTitle))) score += 2;
                            }
                            if (!wantedArtist.isEmpty()) {
                                if (wantedArtist.equals(foundArtist)) score += 5;
                                else if (!foundArtist.isEmpty()
                                         && (foundArtist.contains(wantedArtist)
                                             || wantedArtist.contains(foundArtist))) score += 2;
                            }
                            double duration = item.optDouble("duration", -1);
                            if (wantedDuration > 0 && duration >= 0) {
                                double difference = Math.abs(duration - wantedDuration);
                                score += difference <= 2 ? 3 : (difference <= 6 ? 1 : -2);
                            }
                            if (score > bestScore) {
                                bestScore = score;
                                bestLyrics = synced;
                            }
                        }
                    }
                }
                return bestLyrics;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(java.util.Locale.US)
            .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String makeHttpRequest(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "AimpE3 Music Player (Android)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
