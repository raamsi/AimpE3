package com.example.download;

import org.json.JSONException;
import org.json.JSONObject;

public class DownloadItem {
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_PAUSED = 4;
    public static final int STATUS_SUCCESSFUL = 8;
    public static final int STATUS_FAILED = 16;
    public static final int STATUS_CANCELLED = 32;

    private long downloadId;
    private String url;
    private String title;
    private String fileName;
    private String localFilePath;
    private long totalBytes;
    private long downloadedBytes;
    private int status;
    private int progress;
    private String errorMessage;
    private long timestamp;

    public DownloadItem(long downloadId, String url, String title, String fileName) {
        this.downloadId = downloadId;
        this.url = url;
        this.title = title;
        this.fileName = fileName;
        this.status = STATUS_PENDING;
        this.progress = 0;
        this.totalBytes = 0;
        this.downloadedBytes = 0;
        this.timestamp = System.currentTimeMillis();
    }

    public static DownloadItem fromJson(JSONObject json) {
        if (json == null) return null;
        DownloadItem item = new DownloadItem(
            json.optLong("downloadId", -1),
            json.optString("url", ""),
            json.optString("title", ""),
            json.optString("fileName", "")
        );
        item.localFilePath = json.optString("localFilePath", null);
        item.totalBytes = json.optLong("totalBytes", 0);
        item.downloadedBytes = json.optLong("downloadedBytes", 0);
        item.status = json.optInt("status", STATUS_PENDING);
        item.progress = json.optInt("progress", 0);
        item.errorMessage = json.optString("errorMessage", "");
        item.timestamp = json.optLong("timestamp", System.currentTimeMillis());
        return item;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("downloadId", downloadId);
            json.put("url", url);
            json.put("title", title);
            json.put("fileName", fileName);
            json.put("localFilePath", localFilePath);
            json.put("totalBytes", totalBytes);
            json.put("downloadedBytes", downloadedBytes);
            json.put("status", status);
            json.put("progress", progress);
            json.put("errorMessage", errorMessage);
            json.put("timestamp", timestamp);
        } catch (JSONException ignored) {}
        return json;
    }

    public long getDownloadId() { return downloadId; }
    public void setDownloadId(long downloadId) { this.downloadId = downloadId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getLocalFilePath() { return localFilePath; }
    public void setLocalFilePath(String localFilePath) { this.localFilePath = localFilePath; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getTimestamp() { return timestamp; }

    public boolean isInProgress() {
        return status == STATUS_PENDING || status == STATUS_RUNNING || status == STATUS_PAUSED;
    }

    public boolean isSuccessful() {
        return status == STATUS_SUCCESSFUL;
    }

    public boolean isFailed() {
        return status == STATUS_FAILED || status == STATUS_CANCELLED;
    }
}
