package com.example.download;

public class PresetTrack {
    private final String title;
    private final String artist;
    private final String genre;
    private final String duration;
    private final String url;

    public PresetTrack(String title, String artist, String genre, String duration, String url) {
        this.title = title;
        this.artist = artist;
        this.genre = genre;
        this.duration = duration;
        this.url = url;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getGenre() { return genre; }
    public String getDuration() { return duration; }
    public String getUrl() { return url; }
}
