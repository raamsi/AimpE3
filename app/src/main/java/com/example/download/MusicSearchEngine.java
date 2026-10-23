package com.example.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MusicSearchEngine {
    private static final String TAG = "MusicSearchEngine";
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SearchCallback {
        void onSuccess(List<SearchResultItem> results);
        void onError(String errorMessage);
    }

    public static void search(final String query, final SearchCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) {
                callback.onSuccess(getTrendingTracks());
            }
            return;
        }

        final String cleanQuery = query.trim();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SearchResultItem> combinedResults = new ArrayList<SearchResultItem>();

                // 1. Search Jamendo Music API
                try {
                    List<SearchResultItem> jamendoResults = searchJamendo(cleanQuery);
                    combinedResults.addAll(jamendoResults);
                } catch (Exception e) {
                    Log.w(TAG, "Jamendo search error: " + e.getMessage());
                }

                // 2. Search Internet Archive Audio API
                try {
                    List<SearchResultItem> archiveResults = searchArchiveOrg(cleanQuery);
                    combinedResults.addAll(archiveResults);
                } catch (Exception e) {
                    Log.w(TAG, "Archive.org search error: " + e.getMessage());
                }

                // 3. Match curated catalog for fallback/enrichment
                List<SearchResultItem> curatedMatches = searchCuratedCatalog(cleanQuery);
                for (SearchResultItem item : curatedMatches) {
                    boolean alreadyPresent = false;
                    for (SearchResultItem existing : combinedResults) {
                        if (existing.getTitle().equalsIgnoreCase(item.getTitle())) {
                            alreadyPresent = true;
                            break;
                        }
                    }
                    if (!alreadyPresent) {
                        combinedResults.add(item);
                    }
                }

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            if (combinedResults.isEmpty()) {
                                // If no online results, fallback to curated library matching
                                callback.onSuccess(getTrendingTracks());
                            } else {
                                callback.onSuccess(combinedResults);
                            }
                        }
                    }
                });
            }
        });
    }

    private static List<SearchResultItem> searchJamendo(String query) throws Exception {
        List<SearchResultItem> list = new ArrayList<SearchResultItem>();
        String encoded = URLEncoder.encode(query, "UTF-8");
        String urlString = "https://api.jamendo.com/v3.0/tracks/?client_id=56d30c95&format=json&limit=15&namesearch="
                + encoded + "&audioformat=mp32&include=musicinfo";

        String jsonResponse = getHttpJson(urlString, 5000);
        if (jsonResponse == null || jsonResponse.isEmpty()) return list;

        JSONObject root = new JSONObject(jsonResponse);
        JSONArray results = root.optJSONArray("results");
        if (results == null) return list;

        for (int i = 0; i < results.length(); i++) {
            JSONObject obj = results.optJSONObject(i);
            if (obj == null) continue;

            String id = obj.optString("id", "jam_" + i);
            String title = obj.optString("name", "Titre inconnu");
            String artist = obj.optString("artist_name", "Artiste Jamendo");
            int durationSec = obj.optInt("duration", 180);
            String audioUrl = obj.optString("audio", "");
            String downloadUrl = obj.optString("audiodownload", audioUrl);
            String coverUrl = obj.optString("image", "");

            if (audioUrl.isEmpty()) continue;

            String formattedDuration = String.format(Locale.getDefault(), "%02d:%02d", durationSec / 60, durationSec % 60);

            list.add(new SearchResultItem(
                    "jamendo_" + id,
                    title,
                    artist,
                    formattedDuration,
                    audioUrl,
                    downloadUrl,
                    coverUrl,
                    "Jamendo Music (Licence CC)",
                    "MP3 320k HQ",
                    durationSec * 40000L // Approx size
            ));
        }

        return list;
    }

    private static List<SearchResultItem> searchArchiveOrg(String query) throws Exception {
        List<SearchResultItem> list = new ArrayList<SearchResultItem>();
        String encoded = URLEncoder.encode("mediatype:audio AND " + query, "UTF-8");
        String urlString = "https://archive.org/advancedsearch.php?q=" + encoded
                + "&fl[]=identifier,title,creator,description,downloads,item_size,year&rows=15&page=1&output=json";

        String jsonResponse = getHttpJson(urlString, 5000);
        if (jsonResponse == null || jsonResponse.isEmpty()) return list;

        JSONObject root = new JSONObject(jsonResponse);
        JSONObject responseObj = root.optJSONObject("response");
        if (responseObj == null) return list;

        JSONArray docs = responseObj.optJSONArray("docs");
        if (docs == null) return list;

        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.optJSONObject(i);
            if (doc == null) continue;

            String identifier = doc.optString("identifier", "");
            if (identifier.isEmpty()) continue;

            String title = doc.optString("title", identifier);
            String creator = doc.optString("creator", "Internet Archive Audio");
            long size = doc.optLong("item_size", 4500000L);

            String streamUrl = "https://archive.org/download/" + identifier + "/" + identifier + "_vbr.mp3";
            String downloadUrl = streamUrl;
            String coverUrl = "https://archive.org/services/img/" + identifier;

            list.add(new SearchResultItem(
                    "archive_" + identifier,
                    title,
                    creator,
                    "03:45",
                    streamUrl,
                    downloadUrl,
                    coverUrl,
                    "Internet Archive Audio",
                    "MP3 VBR",
                    size > 0 ? size : 4200000L
            ));
        }

        return list;
    }

    private static String getHttpJson(String urlString, int timeoutMs) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; AimpE3 Snaptube)");
            connection.setRequestProperty("Accept", "application/json");

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP error on " + urlString + ": " + e.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    public static List<SearchResultItem> getTrendingTracks() {
        List<SearchResultItem> list = new ArrayList<SearchResultItem>();

        list.add(new SearchResultItem(
                "trend_1",
                "Synthwave Dreams & Sunset",
                "SoundHelix Project",
                "07:05",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=200&q=80",
                "SoundHelix Audio",
                "MP3 320k HQ",
                6800000L
        ));

        list.add(new SearchResultItem(
                "trend_2",
                "Piano Meditation & Chill",
                "SoundHelix Project",
                "05:44",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                "https://images.unsplash.com/photo-1520523839898-507127054f15?w=200&q=80",
                "SoundHelix Audio",
                "MP3 320k HQ",
                5500000L
        ));

        list.add(new SearchResultItem(
                "trend_3",
                "Upbeat Groove & Bassline",
                "SoundHelix Project",
                "04:14",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=200&q=80",
                "SoundHelix Audio",
                "MP3 320k HQ",
                4200000L
        ));

        list.add(new SearchResultItem(
                "trend_4",
                "Clair de Lune (Debussy)",
                "Claude Debussy (Archive)",
                "05:04",
                "https://archive.org/download/ClairDeLune_675/ClaudeDebussy-SuiteBergamasque-3.ClairDeLune.mp3",
                "https://archive.org/download/ClairDeLune_675/ClaudeDebussy-SuiteBergamasque-3.ClairDeLune.mp3",
                "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&q=80",
                "Internet Archive Musique Classique",
                "MP3 VBR",
                4800000L
        ));

        list.add(new SearchResultItem(
                "trend_5",
                "Moonlight Sonata (Beethoven)",
                "Ludwig van Beethoven",
                "06:15",
                "https://archive.org/download/MoonlightSonata_855/Beethoven-MoonlightSonata.mp3",
                "https://archive.org/download/MoonlightSonata_855/Beethoven-MoonlightSonata.mp3",
                "https://images.unsplash.com/photo-1507838153414-b4b713384a76?w=200&q=80",
                "Internet Archive Musique Classique",
                "MP3 VBR",
                5900000L
        ));

        list.add(new SearchResultItem(
                "trend_6",
                "Lo-Fi Chill Hop Beat",
                "SoundHelix Project",
                "07:54",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3",
                "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3",
                "https://images.unsplash.com/photo-1445985543470-41fba5c3144a?w=200&q=80",
                "SoundHelix Audio",
                "MP3 320k HQ",
                7600000L
        ));

        return list;
    }

    private static List<SearchResultItem> searchCuratedCatalog(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<SearchResultItem> matches = new ArrayList<SearchResultItem>();
        for (SearchResultItem item : getTrendingTracks()) {
            if (item.getTitle().toLowerCase(Locale.ROOT).contains(lower)
                    || item.getArtist().toLowerCase(Locale.ROOT).contains(lower)) {
                matches.add(item);
            }
        }
        return matches;
    }
}
