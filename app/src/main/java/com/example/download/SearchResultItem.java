package com.example.download;

public class SearchResultItem {
    private String id;
    private String title;
    private String artist;
    private String duration;
    private String streamUrl;
    private String downloadUrl;
    private String coverUrl;
    private String source;
    private String qualityBadge;
    private long sizeBytes;

    public SearchResultItem(String id, String title, String artist, String duration,
                            String streamUrl, String downloadUrl, String coverUrl,
                            String source, String qualityBadge, long sizeBytes) {
        this.id = id;
        this.title = title != null && !title.trim().isEmpty() ? title.trim() : "Titre inconnu";
        this.artist = artist != null && !artist.trim().isEmpty() ? artist.trim() : "Artiste inconnu";
        this.duration = duration != null ? duration : "03:30";
        this.streamUrl = streamUrl;
        this.downloadUrl = downloadUrl != null ? downloadUrl : streamUrl;
        this.coverUrl = coverUrl;
        this.source = source != null ? source : "Musique Libre";
        this.qualityBadge = qualityBadge != null ? qualityBadge : "MP3 320k";
        this.sizeBytes = sizeBytes;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getDuration() { return duration; }
    public String getStreamUrl() { return streamUrl; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getCoverUrl() { return coverUrl; }
    public String getSource() { return source; }
    public String getQualityBadge() { return qualityBadge; }
    public long getSizeBytes() { return sizeBytes; }

    public String getFormattedSize() {
        if (sizeBytes <= 0) {
            return "4.2 Mo";
        }
        double mb = (double) sizeBytes / (1024.0 * 1024.0);
        return String.format("%.1f Mo", mb);
    }
}
