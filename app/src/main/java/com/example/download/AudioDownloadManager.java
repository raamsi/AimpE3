package com.example.download;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class AudioDownloadManager {
    private static final String TAG = "AudioDownloadManager";
    private static final String PREF_NAME = "audio_downloads_prefs";
    private static final String KEY_DOWNLOADS_JSON = "downloads_history";

    private static volatile AudioDownloadManager instance;

    private final Context appContext;
    private final DownloadManager downloadManager;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<DownloadItem> downloadItems = new ArrayList<DownloadItem>();
    private final CopyOnWriteArrayList<DownloadListener> listeners = new CopyOnWriteArrayList<DownloadListener>();

    public interface DownloadListener {
        void onDownloadsUpdated();
        void onDownloadFinished(DownloadItem item, boolean success);
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadId != -1) {
                    handleDownloadCompleted(downloadId);
                }
            }
        }
    };

    public static AudioDownloadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AudioDownloadManager.class) {
                if (instance == null) {
                    instance = new AudioDownloadManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AudioDownloadManager(Context context) {
        this.appContext = context;
        this.downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        loadHistory();
        registerReceiver();
    }

    private void registerReceiver() {
        try {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    appContext,
                    downloadReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                );
            } else {
                appContext.registerReceiver(downloadReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering download receiver: " + e.getMessage());
        }
    }

    public void addListener(DownloadListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(DownloadListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized List<DownloadItem> getAllDownloads() {
        return new ArrayList<DownloadItem>(downloadItems);
    }

    public synchronized DownloadItem getDownloadById(long downloadId) {
        for (DownloadItem item : downloadItems) {
            if (item.getDownloadId() == downloadId) {
                return item;
            }
        }
        return null;
    }

    public synchronized DownloadItem enqueueDownload(String rawUrl, String customTitle) throws Exception {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("L'URL de téléchargement ne peut pas être vide.");
        }

        String url = rawUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        Uri uri = Uri.parse(url);

        String title = customTitle != null ? customTitle.trim() : "";
        if (title.isEmpty()) {
            title = extractTitleFromUrl(url);
        }

        String safeFileName = sanitizeFileName(title) + ".mp3";

        // Check if file already exists with this name and rename if needed
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (musicDir != null && !musicDir.exists()) {
            musicDir.mkdirs();
        }

        File targetFile = new File(musicDir, safeFileName);
        int counter = 1;
        String baseName = sanitizeFileName(title);
        while (targetFile.exists()) {
            safeFileName = baseName + "_" + counter + ".mp3";
            targetFile = new File(musicDir, safeFileName);
            counter++;
        }

        DownloadManager.Request request = new DownloadManager.Request(uri);
        request.setTitle(title);
        request.setDescription("Téléchargement MP3 - " + safeFileName);
        request.setMimeType("audio/mpeg");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, safeFileName);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);

        if (downloadManager == null) {
            throw new IllegalStateException("Service de téléchargement Android non disponible.");
        }

        long downloadId = downloadManager.enqueue(request);

        DownloadItem newItem = new DownloadItem(downloadId, url, title, safeFileName);
        newItem.setLocalFilePath(targetFile.getAbsolutePath());
        newItem.setStatus(DownloadItem.STATUS_PENDING);

        downloadItems.add(0, newItem);
        saveHistory();
        notifyListenersUpdated();

        return newItem;
    }

    public synchronized void cancelDownload(long downloadId) {
        DownloadItem item = getDownloadById(downloadId);
        if (item != null) {
            try {
                if (downloadManager != null) {
                    downloadManager.remove(downloadId);
                }
            } catch (Exception ignored) {}
            item.setStatus(DownloadItem.STATUS_CANCELLED);
            item.setErrorMessage("Téléchargement annulé par l'utilisateur");
            saveHistory();
            notifyListenersUpdated();
        }
    }

    public synchronized void retryDownload(DownloadItem item) {
        if (item == null) return;
        try {
            cancelDownload(item.getDownloadId());
            downloadItems.remove(item);
            enqueueDownload(item.getUrl(), item.getTitle());
        } catch (Exception e) {
            item.setStatus(DownloadItem.STATUS_FAILED);
            item.setErrorMessage(e.getMessage());
            saveHistory();
            notifyListenersUpdated();
        }
    }

    public synchronized void deleteDownload(DownloadItem item) {
        if (item == null) return;
        try {
            if (item.isInProgress() && downloadManager != null) {
                downloadManager.remove(item.getDownloadId());
            }

            if (item.getLocalFilePath() != null) {
                File file = new File(item.getLocalFilePath());
                if (file.exists()) {
                    file.delete();
                    // Inform media scanner about file deletion
                    MediaScannerConnection.scanFile(
                        appContext,
                        new String[]{item.getLocalFilePath()},
                        new String[]{"audio/mpeg"},
                        null
                    );
                }
            }
        } catch (Exception ignored) {}

        downloadItems.remove(item);
        saveHistory();
        notifyListenersUpdated();
    }

    public synchronized void clearCompletedDownloads() {
        List<DownloadItem> toKeep = new ArrayList<DownloadItem>();
        for (DownloadItem item : downloadItems) {
            if (item.isInProgress()) {
                toKeep.add(item);
            }
        }
        downloadItems.clear();
        downloadItems.addAll(toKeep);
        saveHistory();
        notifyListenersUpdated();
    }

    public synchronized void queryProgress() {
        if (downloadManager == null) return;

        boolean changed = false;
        for (DownloadItem item : downloadItems) {
            if (!item.isInProgress()) continue;

            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(item.getDownloadId());
            Cursor cursor = null;
            try {
                cursor = downloadManager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    int reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                    int localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

                    int dmStatus = cursor.getInt(statusCol);
                    long downloaded = cursor.getLong(downloadedCol);
                    long total = cursor.getLong(totalCol);

                    item.setDownloadedBytes(downloaded);
                    if (total > 0) {
                        item.setTotalBytes(total);
                        item.setProgress((int) ((downloaded * 100) / total));
                    }

                    if (localUriCol != -1) {
                        String localUriStr = cursor.getString(localUriCol);
                        if (localUriStr != null && item.getLocalFilePath() == null) {
                            try {
                                Uri u = Uri.parse(localUriStr);
                                if ("file".equals(u.getScheme())) {
                                    item.setLocalFilePath(u.getPath());
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    switch (dmStatus) {
                        case DownloadManager.STATUS_RUNNING:
                            item.setStatus(DownloadItem.STATUS_RUNNING);
                            changed = true;
                            break;
                        case DownloadManager.STATUS_PAUSED:
                            item.setStatus(DownloadItem.STATUS_PAUSED);
                            changed = true;
                            break;
                        case DownloadManager.STATUS_PENDING:
                            item.setStatus(DownloadItem.STATUS_PENDING);
                            changed = true;
                            break;
                        case DownloadManager.STATUS_SUCCESSFUL:
                            item.setStatus(DownloadItem.STATUS_SUCCESSFUL);
                            item.setProgress(100);
                            handleSuccessfulItem(item);
                            changed = true;
                            break;
                        case DownloadManager.STATUS_FAILED:
                            item.setStatus(DownloadItem.STATUS_FAILED);
                            int reason = cursor.getInt(reasonCol);
                            item.setErrorMessage("Code d'erreur : " + reason);
                            changed = true;
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying download progress: " + e.getMessage());
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        if (changed) {
            saveHistory();
            notifyListenersUpdated();
        }
    }

    private synchronized void handleDownloadCompleted(long downloadId) {
        DownloadItem item = getDownloadById(downloadId);
        if (item == null) return;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor cursor = null;
        boolean success = false;

        try {
            if (downloadManager != null) {
                cursor = downloadManager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    int reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                    int localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

                    int dmStatus = cursor.getInt(statusCol);
                    if (totalCol != -1) {
                        long total = cursor.getLong(totalCol);
                        if (total > 0) {
                            item.setTotalBytes(total);
                            item.setDownloadedBytes(total);
                        }
                    }

                    if (localUriCol != -1) {
                        String localUriStr = cursor.getString(localUriCol);
                        if (localUriStr != null) {
                            try {
                                Uri u = Uri.parse(localUriStr);
                                if ("file".equals(u.getScheme())) {
                                    item.setLocalFilePath(u.getPath());
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (dmStatus == DownloadManager.STATUS_SUCCESSFUL) {
                        success = true;
                        item.setStatus(DownloadItem.STATUS_SUCCESSFUL);
                        item.setProgress(100);
                        handleSuccessfulItem(item);
                    } else {
                        item.setStatus(DownloadItem.STATUS_FAILED);
                        int reason = cursor.getInt(reasonCol);
                        item.setErrorMessage("Échec du téléchargement (code " + reason + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling download complete: " + e.getMessage());
            item.setStatus(DownloadItem.STATUS_FAILED);
            item.setErrorMessage(e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }

        saveHistory();
        notifyListenersUpdated();
        notifyDownloadFinished(item, success);
    }

    private void handleSuccessfulItem(final DownloadItem item) {
        String filePath = item.getLocalFilePath();
        if (filePath == null) {
            File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            File f = new File(musicDir, item.getFileName());
            if (f.exists()) {
                filePath = f.getAbsolutePath();
                item.setLocalFilePath(filePath);
            }
        }

        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists() && item.getTotalBytes() <= 0) {
                item.setTotalBytes(file.length());
                item.setDownloadedBytes(file.length());
            }

            // Trigger MediaStore scan
            MediaScannerConnection.scanFile(
                appContext,
                new String[]{filePath},
                new String[]{"audio/mpeg"},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        Log.i(TAG, "MediaScanner indexed downloaded MP3: " + path + " -> " + uri);
                    }
                }
            );
        }
    }

    private void notifyListenersUpdated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (DownloadListener listener : listeners) {
                    listener.onDownloadsUpdated();
                }
            }
        });
    }

    private void notifyDownloadFinished(final DownloadItem item, final boolean success) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (DownloadListener listener : listeners) {
                    listener.onDownloadFinished(item, success);
                }
            }
        });
    }

    private synchronized void saveHistory() {
        try {
            JSONArray array = new JSONArray();
            for (DownloadItem item : downloadItems) {
                array.put(item.toJson());
            }
            prefs.edit().putString(KEY_DOWNLOADS_JSON, array.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving downloads history: " + e.getMessage());
        }
    }

    private synchronized void loadHistory() {
        downloadItems.clear();
        String jsonStr = prefs.getString(KEY_DOWNLOADS_JSON, null);
        if (jsonStr != null && !jsonStr.isEmpty()) {
            try {
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    DownloadItem item = DownloadItem.fromJson(obj);
                    if (item != null) {
                        downloadItems.add(item);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading downloads history: " + e.getMessage());
            }
        }
    }

    public static String extractTitleFromUrl(String url) {
        if (url == null || url.isEmpty()) return "Audio_AimpE3";
        try {
            Uri uri = Uri.parse(url);
            String lastPath = uri.getLastPathSegment();
            if (lastPath != null && !lastPath.isEmpty()) {
                if (lastPath.toLowerCase().endsWith(".mp3")) {
                    lastPath = lastPath.substring(0, lastPath.length() - 4);
                }
                return lastPath.replace('-', ' ').replace('_', ' ').trim();
            }
        } catch (Exception ignored) {}
        return "Audio_" + System.currentTimeMillis();
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Audio_" + System.currentTimeMillis();
        }
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (clean.toLowerCase().endsWith(".mp3")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        return clean.isEmpty() ? "Audio_" + System.currentTimeMillis() : clean;
    }

    public List<PresetTrack> getPresetTracks() {
        List<PresetTrack> list = new ArrayList<PresetTrack>();
        list.add(new PresetTrack(
            "SoundHelix Song 1",
            "SoundHelix Project",
            "Synth Electro",
            "13:00",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        ));
        list.add(new PresetTrack(
            "SoundHelix Song 2",
            "SoundHelix Project",
            "Piano & Upbeat",
            "07:05",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"
        ));
        list.add(new PresetTrack(
            "SoundHelix Song 3",
            "SoundHelix Project",
            "Chill Ambient",
            "05:44",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"
        ));
        list.add(new PresetTrack(
            "SoundHelix Song 8",
            "SoundHelix Project",
            "Fast Synthwave",
            "04:14",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3"
        ));
        list.add(new PresetTrack(
            "SoundHelix Song 9",
            "SoundHelix Project",
            "Downtempo Groove",
            "07:54",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3"
        ));
        return list;
    }
}
