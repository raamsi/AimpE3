package com.example;

import android.Manifest;
import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends ComponentActivity {
    private static WeakReference<MainActivity> activeActivity =
        new WeakReference<MainActivity>(null);

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int UI_UPDATE_INTERVAL_MS = 250;
    private static final int LYRICS_UPDATE_INTERVAL_MS = 100;
    private static final int SEEK_STEP_MS = 10000;
    private static final int RESTART_THRESHOLD_MS = 3000;
    private static final int LYRIC_UNSET = -2;

    private static final int COLOR_ACTIVE = 0xFF1DB954;
    private static final int COLOR_INACTIVE = 0xFFAAAAAA;

    private static final String PREFS_NAME = "AimpE3Settings";
    private static final String PREF_REPEAT = "repeat";
    private static final String PREF_SHUFFLE = "shuffle";
    private static final String PREF_CROSSFADE = "crossfade";
    private static final String PREF_EQ_LEVELS = "eq_levels";

    private LinearLayout layoutPlayer, layoutPlaylist;
    private ImageButton btnNavList, btnNavEq, btnNavGrid, btnSearchMain;
    private ImageButton buttonPrev, buttonNext, buttonRewind, buttonForward;
    private ImageButton btnToolRepeat, btnToolShuffle, btnToolEq, btnToolTimer, btnLyrics, btnLike, btnDislike, btnMore;
    private Button buttonPlay;

    private TextView tvMainTitle, tvMainArtist, tvLiveLyric, tvCurrentTime, tvTotalDuration, tvAudioQuality;
    private RgbBorderImageView imgMainCover;
    private ImageView imgBlurredBackground;
    private SpectrumView spectrumView;
    private com.example.visualizer.AudioVisualizerEngine visualizerEngine;
    private SeekBar seekbar1;
    private ListView listview1;
    private EditText etSearchMusic;

    private MediaPlayer mediaPlayer;
    private boolean isCrossfadeEnabled = false;
    private int crossfadeDurationMs = 4000;
    private boolean isNextSongTriggered = false;

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    private AudioEffectManager audioEffectManager;
    private com.example.data.MusicRepository musicRepository;
    private AudioManager audioManager;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final ArrayList<Song> songList = new ArrayList<Song>();
    private final ArrayList<Song> filteredSongList = new ArrayList<Song>();
    private SongAdapter songAdapter;
    private final HashMap<String, String> uriToFilePath = new HashMap<String, String>();

    private boolean isPlaying = false;
    private boolean isUserSeeking = false;
    private boolean resumeOnFocusGain = false;
    private boolean isPreparing = false;
    private boolean startWhenPrepared = true;
    private boolean activityResumed = false;
    private boolean lyricsScreenRequested = false;
    private boolean lyricsScreenVisible = false;
    private boolean isRepeatEnabled = false;
    private boolean isShuffleEnabled = false;
    private boolean isLpfEnabled = false;
    private final short[] savedBandLevelsBeforeLpf = new short[10];

    private int currentSongIndex = 0;
    private String currentSongPath = null;
    private int metadataRequestId = 0;
    private int playbackRequestId = 0;

    private ArrayList<LrcParser.LyricLine> currentLyrics = new ArrayList<LrcParser.LyricLine>();
    private int lastLyricIndex = LYRIC_UNSET;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (mediaPlayer != null && isPlaying
                && (activityResumed || lyricsScreenVisible || lyricsScreenRequested)) {
                handler.postDelayed(this, lyricsScreenVisible
                                    ? LYRICS_UPDATE_INTERVAL_MS : UI_UPDATE_INTERVAL_MS);
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener =
        new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int change) {
                switch (change) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        pausePlayback();
                        abandonAudioFocus();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        if (isPlaying) {
                            pausePlayback();
                            resumeOnFocusGain = true;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        if (mediaPlayer != null) mediaPlayer.setVolume(0.3f, 0.3f);
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        if (mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                        if (resumeOnFocusGain && !isPlaying) {
                            resumeOnFocusGain = false;
                            resumePlayback();
                        }
                        break;
                    default:
                        break;
                }
            }
        };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isPlaying) pausePlayback();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeActivity = new WeakReference<MainActivity>(this);
        setupWindow();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        rescanAudioFiles();
        audioEffectManager = new AudioEffectManager(this);
        musicRepository = new com.example.data.MusicRepository(this);
        bindViews();
        setupList();
        setupControls();
        setupSeekBar();
        restoreToggleStates();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        }

        if (imgMainCover != null) {
            imgMainCover.setAnimating(false);
        }

        checkPermissions();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra("PLAY_FILE_PATH");
        String title = intent.getStringExtra("PLAY_FILE_TITLE");
        if (path != null && !path.isEmpty()) {
            playSongFromPathInternal(path, title);
        }
    }

    void playSongFromPathInternal(String filePath, String title) {
        if (filePath == null) return;
        rescanAudioFiles();
        loadMusicFiles();

        Song target = null;
        for (Song s : songList) {
            if (filePath.equalsIgnoreCase(s.getPath())) {
                target = s;
                break;
            }
        }
        if (target == null) {
            String songTitle = (title != null && !title.isEmpty()) ? title : new File(filePath).getName();
            target = new Song(songTitle, "Téléchargé (AimpE3)", filePath);
            songList.add(0, target);
            filteredSongList.clear();
            filteredSongList.addAll(songList);
            if (songAdapter != null) songAdapter.notifyDataSetChanged();
        }
        playSong(target);
        showPlaylist(false);
        toast("Lecture : " + target.getTitle());
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        lyricsScreenRequested = false;
        lyricsScreenVisible = false;
        if (spectrumView != null && mediaPlayer != null) {
            try {
                spectrumView.linkMediaPlayer(mediaPlayer.getAudioSessionId());
            } catch (Exception ignored) {}
        }
        if (visualizerEngine != null && mediaPlayer != null) {
            try {
                visualizerEngine.attachAudioSession(mediaPlayer.getAudioSessionId());
                visualizerEngine.setPlaying(isPlaying);
            } catch (Exception ignored) {}
        }
        if (mediaPlayer != null) {
            updateProgress();
            if (isPlaying) startProgressUpdates();
        }
        rescanAudioFiles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        if (visualizerEngine != null) {
            visualizerEngine.setPlaying(false);
        }
        if (!lyricsScreenRequested && !lyricsScreenVisible) stopProgressUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        metadataRequestId++;
        playbackRequestId++;
        if (activeActivity.get() == this) {
            activeActivity = new WeakReference<MainActivity>(null);
        }
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);

        if (visualizerEngine != null) {
            visualizerEngine.release();
            visualizerEngine = null;
        }
        if (spectrumView != null) spectrumView.releaseVisualizer();
        releaseEffects();
        releasePlayer();
    }

    private void releaseEffects() {
        if (audioEffectManager != null) {
            audioEffectManager.releaseAll();
        }
        if (equalizer != null) {
            try { equalizer.release(); } catch (Exception ignored) {}
            equalizer = null;
        }
        if (bassBoost != null) {
            try { bassBoost.release(); } catch (Exception ignored) {}
            bassBoost = null;
        }
        if (virtualizer != null) {
            try { virtualizer.release(); } catch (Exception ignored) {}
            virtualizer = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (layoutPlaylist != null && layoutPlaylist.getVisibility() == View.VISIBLE) {
            showPlaylist(false);
        } else {
            super.onBackPressed();
        }
    }

    private void setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }

    private void bindViews() {
        layoutPlayer = (LinearLayout) findViewById(R.id.layout_player);
        layoutPlaylist = (LinearLayout) findViewById(R.id.layout_playlist);

        btnSearchMain = (ImageButton) findViewById(R.id.btnSearchMain);
        if (btnSearchMain != null) {
            btnSearchMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent intent = new Intent(MainActivity.this, SearchActivity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Erreur d'ouverture : " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        etSearchMusic = (EditText) findViewById(R.id.etSearchMusic);
        if (etSearchMusic != null) {
            etSearchMusic.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterMusicList(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        btnLyrics = (ImageButton) findViewById(R.id.btnLyrics);
        btnNavList = (ImageButton) findViewById(R.id.btn_nav_list);
        btnNavEq = (ImageButton) findViewById(R.id.btn_nav_eq);
        btnNavGrid = (ImageButton) findViewById(R.id.btn_nav_grid);

        buttonPlay = (Button) findViewById(R.id.buttonPlay);
        buttonPrev = (ImageButton) findViewById(R.id.buttonPrev);
        buttonNext = (ImageButton) findViewById(R.id.buttonNext);
        buttonRewind = (ImageButton) findViewById(R.id.buttonRewind);
        buttonForward = (ImageButton) findViewById(R.id.buttonForward);

        btnToolRepeat = (ImageButton) findViewById(R.id.btnToolRepeat);
        btnToolShuffle = (ImageButton) findViewById(R.id.btnToolShuffle);
        btnToolEq = (ImageButton) findViewById(R.id.btnToolEq);
        btnToolTimer = (ImageButton) findViewById(R.id.btnToolTimer);
        btnLike = (ImageButton) findViewById(R.id.btnLike);
        btnDislike = (ImageButton) findViewById(R.id.btnDislike);
        btnMore = (ImageButton) findViewById(R.id.btnMore);

        tvMainTitle = (TextView) findViewById(R.id.tvMainTitle);
        tvMainArtist = (TextView) findViewById(R.id.tvMainArtist);
        tvLiveLyric = (TextView) findViewById(R.id.tvLiveLyric);
        tvCurrentTime = (TextView) findViewById(R.id.tvCurrentTime);
        tvTotalDuration = (TextView) findViewById(R.id.tvTotalDuration);
        tvAudioQuality = (TextView) findViewById(R.id.tvAudioQuality);

        if (tvMainTitle != null) tvMainTitle.setSelected(true);
        if (tvMainArtist != null) tvMainArtist.setSelected(true);

        imgMainCover = (RgbBorderImageView) findViewById(R.id.imgMainCover);
        imgBlurredBackground = (ImageView) findViewById(R.id.imgBlurredBackground);
        spectrumView = (SpectrumView) findViewById(R.id.spectrumView);
        androidx.compose.ui.platform.ComposeView composeVisualizerView = findViewById(R.id.composeVisualizerView);
        if (composeVisualizerView != null) {
            visualizerEngine = new com.example.visualizer.AudioVisualizerEngine(this);
            com.example.visualizer.AudioVisualizerBridge.setupVisualizerView(composeVisualizerView, visualizerEngine);
        }
        seekbar1 = (SeekBar) findViewById(R.id.seekbar1);
        listview1 = (ListView) findViewById(R.id.listview1);
    }

    private void setupList() {
        songAdapter = new SongAdapter(this, filteredSongList);
        if (listview1 != null) {
            listview1.setAdapter(songAdapter);
            listview1.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < filteredSongList.size()) {
                        Song selected = filteredSongList.get(position);
                        int originalIndex = songList.indexOf(selected);
                        if (originalIndex >= 0) {
                            playSongAt(originalIndex);
                        } else {
                            playSong(selected);
                        }
                        showPlaylist(false);
                    }
                }
            });
        }
    }

    private void filterMusicList(String query) {
        filteredSongList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredSongList.addAll(songList);
        } else {
            String lower = query.toLowerCase(Locale.getDefault()).trim();
            for (Song s : songList) {
                if (s.getTitle().toLowerCase(Locale.getDefault()).contains(lower)
                    || s.getArtist().toLowerCase(Locale.getDefault()).contains(lower)) {
                    filteredSongList.add(s);
                }
            }
        }
        if (songAdapter != null) songAdapter.notifyDataSetChanged();
    }

    private void setupControls() {
        if (buttonPlay != null) {
            buttonPlay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { togglePlayPause(); }
            });
        }
        if (buttonNext != null) {
            buttonNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playNext(); }
            });
        }
        if (buttonPrev != null) {
            buttonPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playPrevious(); }
            });
        }
        if (buttonForward != null) {
            buttonForward.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { seekBy(SEEK_STEP_MS); }
            });
        }
        if (buttonRewind != null) {
            buttonRewind.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { seekBy(-SEEK_STEP_MS); }
            });
        }
        if (btnToolRepeat != null) {
            btnToolRepeat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isRepeatEnabled = !isRepeatEnabled;
                    prefs.edit().putBoolean(PREF_REPEAT, isRepeatEnabled).apply();
                    applyToggleColor(btnToolRepeat, isRepeatEnabled);
                    if (mediaPlayer != null) {
                        try { mediaPlayer.setLooping(isRepeatEnabled); } catch (Exception ignored) {}
                    }
                    toast(isRepeatEnabled ? "Répétition activée" : "Répétition désactivée");
                }
            });
        }
        if (btnToolShuffle != null) {
            btnToolShuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isShuffleEnabled = !isShuffleEnabled;
                    prefs.edit().putBoolean(PREF_SHUFFLE, isShuffleEnabled).apply();
                    applyToggleColor(btnToolShuffle, isShuffleEnabled);
                    toast(isShuffleEnabled ? "Lecture aléatoire activée" : "Lecture aléatoire désactivée");
                }
            });
        }
        if (btnLyrics != null) {
            btnLyrics.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openLyricsScreen(); }
            });
        }

        View.OnClickListener eqClickListener = new View.OnClickListener() {
            @Override public void onClick(View v) { showEqualizerDialog(); }
        };
        if (btnToolEq != null) btnToolEq.setOnClickListener(eqClickListener);
        if (btnNavEq != null) btnNavEq.setOnClickListener(eqClickListener);

        if (btnNavList != null) {
            btnNavList.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPlaylist(layoutPlaylist.getVisibility() != View.VISIBLE); }
            });
        }
        if (btnNavGrid != null) {
            btnNavGrid.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPlaylist(false); }
            });
        }

        if (btnLike != null) {
            btnLike.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toast("Ajouté aux favoris ❤️"); }
            });
        }
        if (btnDislike != null) {
            btnDislike.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toast("Morceau ignoré"); playNext(); }
            });
        }
        if (btnMore != null) {
            btnMore.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showMoreOptionsDialog(); }
            });
        }
        if (btnToolTimer != null) {
            btnToolTimer.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTimerDialog(); }
            });
        }
    }

    private void showTimerDialog() {
        final String[] options = new String[]{"Désactivé", "10 minutes", "20 minutes", "30 minutes", "45 minutes", "60 minutes"};
        final int[] minutes = new int[]{0, 10, 20, 30, 45, 60};

        new AlertDialog.Builder(this)
            .setTitle("Minuteur de sommeil")
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int min = minutes[which];
                    if (min == 0) {
                        toast("Minuteur désactivé");
                    } else {
                        toast("Lecture coupée dans " + min + " min");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { pausePlayback(); }
                        }, min * 60000L);
                    }
                }
            })
            .show();
    }

    private void showMoreOptionsDialog() {
        final String[] items = new String[]{
            "Partager le morceau",
            "Ajuster les temps en direct (Compose)",
            "Mes Playlists (Room)",
            "Ajouter à une playlist",
            "Télécharger d'autres musiques",
            "Ajouter des paroles manuellement"
        };
        new AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        if (currentSongPath != null) {
                            try {
                                Intent share = new Intent(Intent.ACTION_SEND);
                                share.setType("audio/*");
                                share.putExtra(Intent.EXTRA_STREAM, Uri.parse(currentSongPath));
                                startActivity(Intent.createChooser(share, "Partager avec"));
                            } catch (Exception e) {
                                toast("Impossible de partager ce fichier");
                            }
                        }
                    } else if (which == 1) {
                        if (currentLyrics != null && !currentLyrics.isEmpty()) {
                            ArrayList<String> texts = new ArrayList<String>();
                            ArrayList<Integer> times = new ArrayList<Integer>();
                            for (LrcParser.LyricLine line : currentLyrics) {
                                texts.add(line.text);
                                times.add(line.timeMs);
                            }
                            Intent intent = new Intent(MainActivity.this, LyricsAdjusterActivity.class);
                            intent.putStringArrayListExtra("EDIT_LYRICS_TEXT", texts);
                            intent.putIntegerArrayListExtra("EDIT_LYRICS_TIME", times);
                            intent.putExtra("SONG_TITLE", tvMainTitle != null ? tvMainTitle.getText().toString() : "");
                            startActivity(intent);
                        } else {
                            toast("Aucune parole chargée à ajuster");
                        }
                    } else if (which == 2) {
                        showPlaylistsManagerDialog();
                    } else if (which == 3) {
                        showAddToPlaylistDialog();
                    } else if (which == 4) {
                        startActivity(new Intent(MainActivity.this, SearchActivity.class));
                    } else if (which == 5) {
                        showManualLyricsInputDialog();
                    }
                }
            })
            .show();
    }

    private void showPlaylistsManagerDialog() {
        if (musicRepository == null) return;
        musicRepository.getAllPlaylistsAsync(new com.example.data.MusicRepository.Callback<java.util.List<com.example.data.PlaylistWithSongCount>>() {
            @Override
            public void onResult(final java.util.List<com.example.data.PlaylistWithSongCount> playlists) {
                if (isFinishing()) return;
                ArrayList<String> options = new ArrayList<String>();
                options.add("➕ Créer une nouvelle playlist");
                for (com.example.data.PlaylistWithSongCount p : playlists) {
                    options.add(p.getName() + " (" + p.getSongCount() + " morceaux)");
                }

                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Mes Playlists (Room)")
                    .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                showCreatePlaylistDialog(null);
                            } else {
                                final com.example.data.PlaylistWithSongCount selected = playlists.get(which - 1);
                                showPlaylistActionDialog(selected);
                            }
                        }
                    })
                    .setNegativeButton("Fermer", null)
                    .show();
            }
        });
    }

    private void showPlaylistActionDialog(final com.example.data.PlaylistWithSongCount playlist) {
        final String[] actions = new String[]{
            "▶️ Charger et écouter cette playlist",
            "🗑️ Supprimer cette playlist"
        };
        new AlertDialog.Builder(this)
            .setTitle(playlist.getName())
            .setItems(actions, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        loadAndPlayPlaylist(playlist.getId(), playlist.getName());
                    } else if (which == 1) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Supprimer la playlist")
                            .setMessage("Êtes-vous sûr de vouloir supprimer la playlist \"" + playlist.getName() + "\" ?")
                            .setPositiveButton("Supprimer", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    musicRepository.deletePlaylistAsync(playlist.getId(), new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("Playlist \"" + playlist.getName() + "\" supprimée");
                                        }
                                    });
                                }
                            })
                            .setNegativeButton("Annuler", null)
                            .show();
                    }
                }
            })
            .setNegativeButton("Retour", null)
            .show();
    }

    private void loadAndPlayPlaylist(final long playlistId, final String playlistName) {
        if (musicRepository == null) return;
        musicRepository.getSongsForPlaylistAsync(playlistId, new com.example.data.MusicRepository.Callback<java.util.List<com.example.data.PlaylistSongEntity>>() {
            @Override
            public void onResult(java.util.List<com.example.data.PlaylistSongEntity> songs) {
                if (songs.isEmpty()) {
                    toast("Cette playlist est vide. Ajoutez-y des morceaux !");
                    return;
                }
                songList.clear();
                for (com.example.data.PlaylistSongEntity s : songs) {
                    songList.add(new Song(s.getTitle(), s.getArtist(), s.getPath()));
                }
                filteredSongList.clear();
                filteredSongList.addAll(songList);
                if (songAdapter != null) songAdapter.notifyDataSetChanged();
                musicRepository.saveQueueAsync(songList, songList.get(0).getPath());
                currentSongIndex = 0;
                playSong(songList.get(0));
                toast("Lecture de la playlist \"" + playlistName + "\" (" + songs.size() + " morceaux)");
            }
        });
    }

    private void showAddToPlaylistDialog() {
        if (songList.isEmpty() && currentSongPath == null) {
            toast("Aucun morceau disponible à ajouter");
            return;
        }
        final Song songToAdd;
        if (currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            songToAdd = songList.get(currentSongIndex);
        } else if (currentSongPath != null) {
            String title = tvMainTitle != null ? tvMainTitle.getText().toString() : "Morceau inconnu";
            String artist = tvMainArtist != null ? tvMainArtist.getText().toString() : "Artiste inconnu";
            songToAdd = new Song(title, artist, currentSongPath);
        } else {
            toast("Veuillez sélectionner ou lancer un morceau");
            return;
        }

        if (musicRepository == null) return;
        musicRepository.getAllPlaylistsAsync(new com.example.data.MusicRepository.Callback<java.util.List<com.example.data.PlaylistWithSongCount>>() {
            @Override
            public void onResult(final java.util.List<com.example.data.PlaylistWithSongCount> playlists) {
                if (isFinishing()) return;
                ArrayList<String> options = new ArrayList<String>();
                options.add("➕ Créer une nouvelle playlist");
                for (com.example.data.PlaylistWithSongCount p : playlists) {
                    options.add(p.getName() + " (" + p.getSongCount() + " morceaux)");
                }

                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Ajouter \"" + songToAdd.getTitle() + "\" à :")
                    .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                showCreatePlaylistDialog(songToAdd);
                            } else {
                                final com.example.data.PlaylistWithSongCount selected = playlists.get(which - 1);
                                musicRepository.addSongToPlaylistAsync(selected.getId(), songToAdd, new com.example.data.MusicRepository.Callback<Long>() {
                                    @Override
                                    public void onResult(Long newId) {
                                        toast("Ajouté à la playlist \"" + selected.getName() + "\"");
                                    }
                                });
                            }
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            }
        });
    }

    private void showCreatePlaylistDialog(final Song songToAddAfter) {
        final EditText input = new EditText(this);
        input.setHint("Nom de la playlist (ex: Mes Tops, Soirée...)");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x77FFFFFF);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(16), dp(16), dp(16));

        new AlertDialog.Builder(this)
            .setTitle("Nouvelle Playlist")
            .setView(input)
            .setPositiveButton("Créer", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Le nom de la playlist ne peut pas être vide");
                        return;
                    }
                    if (musicRepository != null) {
                        musicRepository.createPlaylistAsync(name, new com.example.data.MusicRepository.Callback<Long>() {
                            @Override
                            public void onResult(final Long newId) {
                                if (songToAddAfter != null) {
                                    musicRepository.addSongToPlaylistAsync(newId, songToAddAfter, new com.example.data.MusicRepository.Callback<Long>() {
                                        @Override
                                        public void onResult(Long id) {
                                            toast("Playlist \"" + name + "\" créée avec \"" + songToAddAfter.getTitle() + "\" !");
                                        }
                                    });
                                } else {
                                    toast("Playlist \"" + name + "\" créée avec succès !");
                                }
                            }
                        });
                    }
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void setupSeekBar() {
        if (seekbar1 != null) {
            seekbar1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && tvCurrentTime != null) {
                        tvCurrentTime.setText(formatTime(progress));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    isUserSeeking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    isUserSeeking = false;
                    if (mediaPlayer != null && !isPreparing) {
                        mediaPlayer.seekTo(seekBar.getProgress());
                        updateProgress();
                    }
                }
            });
        }
    }

    private void restoreToggleStates() {
        isRepeatEnabled = prefs.getBoolean(PREF_REPEAT, false);
        isShuffleEnabled = prefs.getBoolean(PREF_SHUFFLE, false);
        isCrossfadeEnabled = prefs.getBoolean(PREF_CROSSFADE, false);
        if (btnToolRepeat != null) applyToggleColor(btnToolRepeat, isRepeatEnabled);
        if (btnToolShuffle != null) applyToggleColor(btnToolShuffle, isShuffleEnabled);
    }

    private void applyToggleColor(ImageButton button, boolean active) {
        if (button != null) {
            button.setColorFilter(active ? COLOR_ACTIVE : COLOR_INACTIVE);
        }
    }

    private void showPlaylist(boolean show) {
        if (layoutPlaylist != null) layoutPlaylist.setVisibility(show ? View.VISIBLE : View.GONE);
        if (layoutPlayer != null) layoutPlayer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String storagePermission() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasStoragePermission() {
        return checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissions() {
        ArrayList<String> needed = new ArrayList<String>();
        if (!hasStoragePermission()) needed.add(storagePermission());
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            loadMusicFiles();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            loadMusicFiles();
        }
    }

    private void loadMusicFiles() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Song> loaded = new ArrayList<Song>();
                final HashMap<String, String> paths = new HashMap<String, String>();
                queryMusic(loaded, paths);

                // Morceaux de démonstration si l'appareil/émulateur n'a pas encore de musique locale
                if (loaded.isEmpty()) {
                    loaded.add(new Song("SoundHelix Song 1 (Demo HD)", "SoundHelix", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"));
                    loaded.add(new Song("SoundHelix Song 2 (Club Electro)", "SoundHelix", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"));
                    loaded.add(new Song("SoundHelix Song 3 (Ambient Dub)", "SoundHelix", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"));
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        uriToFilePath.clear();
                        uriToFilePath.putAll(paths);
                        songList.clear();
                        songList.addAll(loaded);
                        filterMusicList(etSearchMusic != null ? etSearchMusic.getText().toString() : "");
                        if (musicRepository != null) {
                            musicRepository.saveQueueAsync(loaded, currentSongPath);
                        }
                    }
                });
            }
        }).start();
    }

    private void rescanAudioFiles() {
        try {
            File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (musicDir != null && musicDir.exists()) {
                MediaScannerConnection.scanFile(
                    this,
                    new String[]{musicDir.getAbsolutePath()},
                    null,
                    null
                );
            }
        } catch (Exception ignored) {}
    }

    private void queryMusic(ArrayList<Song> result, HashMap<String, String> uriToPath) {
        HashSet<String> knownPaths = new HashSet<String>();
        Cursor cursor = null;

        try {
            String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            };

            cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.TITLE + " ASC");

            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String title = cursor.getString(titleCol);
                    String artist = cursor.getString(artistCol);
                    String data = cursor.getString(dataCol);

                    if (title == null) title = "Musique sans titre";
                    if (artist == null || artist.equals("<unknown>")) artist = "Artiste inconnu";

                    String uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString();
                    result.add(new Song(title, artist, uri));

                    if (data != null) {
                        knownPaths.add(data);
                        uriToPath.put(uri, data);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        try {
            File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (musicDir != null && musicDir.isDirectory()) scanFolder(musicDir, result, knownPaths, 0);

            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir != null && downloadDir.isDirectory()) scanFolder(downloadDir, result, knownPaths, 0);
        } catch (Exception ignored) {}

        Collections.sort(result, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });
    }

    private void scanFolder(File dir, ArrayList<Song> result, HashSet<String> knownPaths, int depth) {
        if (depth > 8) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".")) continue;

            if (file.isDirectory()) {
                scanFolder(file, result, knownPaths, depth + 1);
            } else if (isAudioFile(name)) {
                String path = file.getAbsolutePath();
                if (knownPaths.add(path)) {
                    int dot = name.lastIndexOf('.');
                    String title = dot > 0 ? name.substring(0, dot) : name;
                    result.add(new Song(title, "Dossier Local", path));
                }
            }
        }
    }

    private boolean isAudioFile(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac")
            || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".aac");
    }

    private void playSongAt(int index) {
        if (songList.isEmpty() || index < 0 || index >= songList.size()) return;
        currentSongIndex = index;
        playSong(songList.get(index));
    }

    private void playNext() {
        if (songList.isEmpty()) return;
        int size = songList.size();
        int next;
        if (isShuffleEnabled && size > 1) {
            do {
                next = random.nextInt(size);
            } while (next == currentSongIndex);
        } else {
            next = (currentSongIndex + 1) % size;
        }
        playSongAt(next);
    }

    private void playPrevious() {
        if (songList.isEmpty()) return;

        if (mediaPlayer != null && !isPreparing
            && mediaPlayer.getCurrentPosition() > RESTART_THRESHOLD_MS) {
            mediaPlayer.seekTo(0);
            updateProgress();
            return;
        }
        int size = songList.size();
        playSongAt((currentSongIndex - 1 + size) % size);
    }

    private void togglePlayPause() {
        resumeOnFocusGain = false;
        if (mediaPlayer == null) {
            if (songList.isEmpty()) {
                toast("Aucune musique disponible");
            } else {
                playSongAt(0);
            }
        } else if (isPreparing) {
            startWhenPrepared = !startWhenPrepared;
            if (buttonPlay != null) buttonPlay.setText(startWhenPrepared ? "⏸" : "▶");
        } else if (isPlaying) {
            pausePlayback();
        } else {
            resumePlayback();
        }
    }

    private void pausePlayback() {
        resumeOnFocusGain = false;
        if (mediaPlayer != null) {
            try { mediaPlayer.pause(); } catch (IllegalStateException ignored) {}
        }
        setPlayingState(false);
    }

    private void resumePlayback() {
        if (mediaPlayer == null || isPreparing) return;
        if (!requestAudioFocus()) {
            toast("Impossible d'obtenir le focus audio");
            return;
        }
        try {
            mediaPlayer.start();
            setPlayingState(true);
        } catch (IllegalStateException e) {
            setPlayingState(false);
        }
    }

    private void setPlayingState(boolean playing) {
        isPlaying = playing;
        if (buttonPlay != null) buttonPlay.setText(playing ? "⏸" : "▶");
        if (imgMainCover != null) imgMainCover.setAnimating(playing);
        if (visualizerEngine != null) {
            visualizerEngine.setPlaying(playing);
        }
        if (playing) {
            startProgressUpdates();
        } else {
            stopProgressUpdates();
        }
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || isPreparing) return;
        int target = mediaPlayer.getCurrentPosition() + deltaMs;
        target = Math.max(0, Math.min(mediaPlayer.getDuration(), target));
        mediaPlayer.seekTo(target);
        updateProgress();
    }

    private void releasePlayer() {
        stopProgressUpdates();
        isPreparing = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void playSong(final Song song) {
        if (song == null) return;

        final int requestId = ++playbackRequestId;
        isNextSongTriggered = false;

        if (isCrossfadeEnabled && mediaPlayer != null && isPlaying) {
            final MediaPlayer oldPlayer = mediaPlayer;
            mediaPlayer = null;

            final String path = song.getPath();
            currentSongPath = path;
            setLyrics(null);
            updateLiveLyric(0);

            final MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            isPreparing = true;
            startWhenPrepared = true;
            if (buttonPlay != null) buttonPlay.setText("⏸");

            try {
                configureMediaPlayerAudioAttributes(mp);
                setDataSourceForPlayer(mp, path);
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer player) {
                        if (player != mediaPlayer || requestId != playbackRequestId || isFinishing()) {
                            player.release();
                            return;
                        }
                        isPreparing = false;
                        player.setLooping(isRepeatEnabled);
                        player.setVolume(0f, 0f);
                        try {
                            player.start();
                            setPlayingState(true);
                        } catch (Exception e) {
                            releasePlayer();
                            return;
                        }

                        startCrossfadeEffect(oldPlayer, player);
                        onSongPreparedUi(song, player);
                    }
                });
                setupPlayerCompletionAndErrors(mp);
                mp.prepareAsync();
            } catch (Exception e) {
                isPreparing = false;
                setPlayingState(false);
                toast("Erreur de lecture du morceau");
            }
            return;
        }

        releasePlayer();
        setPlayingState(false);

        final String path = song.getPath();
        currentSongPath = path;
        setLyrics(null);
        updateLiveLyric(0);

        final MediaPlayer mp = new MediaPlayer();
        mediaPlayer = mp;
        isPreparing = true;
        startWhenPrepared = true;
        if (buttonPlay != null) buttonPlay.setText("⏸");

        try {
            configureMediaPlayerAudioAttributes(mp);
            setDataSourceForPlayer(mp, path);

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (player != mediaPlayer || requestId != playbackRequestId || isFinishing()) {
                        player.release();
                        return;
                    }
                    isPreparing = false;
                    player.setLooping(isRepeatEnabled);
                    if (startWhenPrepared && !requestAudioFocus()) {
                        releasePlayer();
                        setPlayingState(false);
                        toast("Impossible d'obtenir le focus audio");
                        return;
                    }
                    if (startWhenPrepared) {
                        try {
                            player.start();
                            setPlayingState(true);
                        } catch (IllegalStateException e) {
                            releasePlayer();
                            setPlayingState(false);
                            toast("Impossible de démarrer ce morceau");
                            return;
                        }
                    } else {
                        setPlayingState(false);
                    }

                    onSongPreparedUi(song, player);
                }
            });

            setupPlayerCompletionAndErrors(mp);
            mp.prepareAsync();
        } catch (Exception e) {
            isPreparing = false;
            if (mediaPlayer == mp) releasePlayer();
            else mp.release();
            setPlayingState(false);
            toast("Impossible de lire ce morceau");
        }
    }

    private void configureMediaPlayerAudioAttributes(MediaPlayer mp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                                  .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                  .setUsage(AudioAttributes.USAGE_MEDIA)
                                  .build());
        } else {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

    private void setDataSourceForPlayer(MediaPlayer mp, String path) throws Exception {
        if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://")) {
            mp.setDataSource(this, Uri.parse(path));
        } else {
            mp.setDataSource(path);
        }
    }

    private void setupPlayerCompletionAndErrors(MediaPlayer mp) {
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer player) {
                if (mediaPlayer == player) {
                    playNext();
                }
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer player, int what, int extra) {
                if (mediaPlayer == player) {
                    releasePlayer();
                    setPlayingState(false);
                    toast("Erreur de flux audio");
                }
                return true;
            }
        });
    }

    private void onSongPreparedUi(Song song, MediaPlayer player) {
        if (tvMainTitle != null) tvMainTitle.setText(song.getTitle());
        if (tvMainArtist != null) tvMainArtist.setText(song.getArtist());
        int duration = player.getDuration();
        if (seekbar1 != null) {
            seekbar1.setMax(duration);
            seekbar1.setProgress(0);
        }
        if (tvCurrentTime != null) tvCurrentTime.setText(formatTime(0));
        if (tvTotalDuration != null) tvTotalDuration.setText(formatTime(duration));

        int sessionId = player.getAudioSessionId();
        if (visualizerEngine != null) {
            visualizerEngine.attachAudioSession(sessionId);
            visualizerEngine.setPlaying(true);
        }
        if (spectrumView != null && !lyricsScreenVisible) {
            spectrumView.linkMediaPlayer(sessionId);
        }
        LyricsActivity.updateAudioSession(sessionId);
        if (audioEffectManager != null) {
            audioEffectManager.attachToAudioSession(sessionId);
        }
        setupEqualizer(sessionId);
        if (musicRepository != null) {
            musicRepository.updateCurrentlyPlayingAsync(song.getPath());
        }
        loadTrackMetadata(song.getPath());
        loadLyrics(song, duration);
    }

    private void startCrossfadeEffect(final MediaPlayer outPlayer, final MediaPlayer inPlayer) {
        if (outPlayer == null || inPlayer == null) return;
        final int steps = 40;
        final long interval = Math.max(20, crossfadeDurationMs / steps);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i <= steps; i++) {
                        float volumeOut = 1.0f - ((float) i / steps);
                        float volumeIn = (float) i / steps;

                        try {
                            if (outPlayer.isPlaying()) outPlayer.setVolume(volumeOut, volumeOut);
                            if (inPlayer.isPlaying()) inPlayer.setVolume(volumeIn, volumeIn);
                        } catch (Exception ignored) {}

                        Thread.sleep(interval);
                    }
                    try {
                        outPlayer.stop();
                        outPlayer.release();
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    try { inPlayer.setVolume(1.0f, 1.0f); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        return audioManager.requestAudioFocus(focusListener,
                                              AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
    }

    private void startProgressUpdates() {
        handler.removeCallbacks(progressRunnable);
        handler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        handler.removeCallbacks(progressRunnable);
    }

    private void updateProgress() {
        if (mediaPlayer == null) return;
        int position;
        int duration;
        try {
            position = mediaPlayer.getCurrentPosition();
            duration = mediaPlayer.getDuration();
        } catch (IllegalStateException e) {
            return;
        }

        if (!isUserSeeking && seekbar1 != null) {
            seekbar1.setProgress(position);
            if (tvCurrentTime != null) tvCurrentTime.setText(formatTime(position));
        }
        if (activityResumed) updateLiveLyric(position);
        LyricsActivity.updatePlaybackPosition(position);

        if (isCrossfadeEnabled && isPlaying && !isNextSongTriggered && duration > 8000) {
            if (duration - position <= crossfadeDurationMs) {
                isNextSongTriggered = true;
                playNext();
            }
        }
    }

    private String formatTime(int ms) {
        int totalSeconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void loadTrackMetadata(final String path) {
        final int requestId = ++metadataRequestId;

        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap art = null;
                Bitmap blurred = null;
                String quality = "";

                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                MediaExtractor extractor = new MediaExtractor();
                int sampleRate = 44100;
                int bitRate = 0;
                String format = "AUDIO";

                try {
                    if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://")) {
                        Uri uri = Uri.parse(path);
                        retriever.setDataSource(MainActivity.this, uri);
                        extractor.setDataSource(MainActivity.this, uri, null);
                    } else {
                        retriever.setDataSource(path);
                        extractor.setDataSource(path);
                    }

                    String bitRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                    if (bitRateStr != null) bitRate = Integer.parseInt(bitRateStr) / 1000;

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat mf = extractor.getTrackFormat(i);
                        String mime = mf.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            format = mimeToLabel(mime);
                            if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            }
                            break;
                        }
                    }

                    byte[] artBytes = retriever.getEmbeddedPicture();
                    if (artBytes != null) {
                        art = decodeSampledBitmap(artBytes, 1024);
                        if (art != null) blurred = createBlurBitmap(art);
                    }
                } catch (Exception ignored) {
                } finally {
                    try { extractor.release(); } catch (Exception ignored) {}
                    try { retriever.release(); } catch (Exception ignored) {}
                }

                double khz = sampleRate / 1000.0;
                quality = (bitRate > 0)
                    ? String.format(Locale.US, "%.1f KHZ %d KBPS %s", khz, bitRate, format)
                    : String.format(Locale.US, "%.1f KHZ %s", khz, format);

                final Bitmap fArt = art;
                final Bitmap fBlur = blurred;
                final String fQuality = quality;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || requestId != metadataRequestId) return;
                        applyTrackMetadata(fArt, fBlur, fQuality);
                    }
                });
            }
        }).start();
    }

    private void applyTrackMetadata(Bitmap art, Bitmap blurred, String quality) {
        if (tvAudioQuality != null) tvAudioQuality.setText(quality);
        if (imgMainCover != null) {
            imgMainCover.setBackgroundColor(Color.TRANSPARENT);
            if (art != null) {
                imgMainCover.setImageBitmap(art);
                crossfadeBackground(blurred);
            } else {
                imgMainCover.setImageDrawable(new ColorDrawable(0xFF22222A));
                crossfadeBackground(null);
            }
            animateCoverIn();
        }
    }

    private void animateCoverIn() {
        if (imgMainCover == null) return;
        imgMainCover.setAlpha(0f);
        imgMainCover.setScaleX(0.92f);
        imgMainCover.setScaleY(0.92f);
        imgMainCover.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(350)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void crossfadeBackground(Bitmap blurred) {
        if (imgBlurredBackground == null) return;
        if (blurred == null) {
            imgBlurredBackground.setImageDrawable(null);
            return;
        }

        Drawable old = imgBlurredBackground.getDrawable();
        if (old instanceof TransitionDrawable) {
            old = ((TransitionDrawable) old).getDrawable(1);
        }

        BitmapDrawable fresh = new BitmapDrawable(getResources(), blurred);
        if (old == null) {
            imgBlurredBackground.setImageDrawable(fresh);
        } else {
            TransitionDrawable transition = new TransitionDrawable(new Drawable[]{old, fresh});
            transition.setCrossFadeEnabled(true);
            imgBlurredBackground.setImageDrawable(transition);
            transition.startTransition(450);
        }
    }

    private Bitmap decodeSampledBitmap(byte[] data, int maxSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private Bitmap createBlurBitmap(Bitmap source) {
        try {
            int w = 48;
            int h = Math.max(1, Math.round(48f * source.getHeight() / source.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(source, w, h, true)
                .copy(Bitmap.Config.ARGB_8888, true);

            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < 3; i++) {
                px = blurPass(px, w, h, 3, true);
                px = blurPass(px, w, h, 3, false);
            }
            small.setPixels(px, 0, w, 0, 0, w, h);
            return small;
        } catch (Exception e) {
            return null;
        }
    }

    private int[] blurPass(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] out = new int[src.length];
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;

        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int c = horizontal ? src[line * w + j] : src[j * w + line];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    count++;
                }
                int idx = horizontal ? line * w + i : i * w + line;
                out[idx] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        return out;
    }

    private String mimeToLabel(String mime) {
        String m = mime.toLowerCase(Locale.US);
        if (m.contains("mpeg")) return "MP3";
        if (m.contains("mp4a") || m.contains("aac")) return "AAC";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("vorbis")) return "OGG";
        if (m.contains("opus")) return "OPUS";
        if (m.contains("wav") || m.contains("raw")) return "WAV";
        if (m.contains("amr")) return "AMR";
        return m.substring(m.indexOf('/') + 1).toUpperCase(Locale.US);
    }

    private String resolveFilePath(String songPath) {
        if (songPath != null && songPath.startsWith("content://")) {
            String real = uriToFilePath.get(songPath);
            if (real != null) return real;
            try {
                Cursor cursor = getContentResolver().query(
                    Uri.parse(songPath), 
                    new String[]{MediaStore.Audio.Media.DATA}, 
                    null, null, null
                );
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                        if (idx != -1) {
                            String path = cursor.getString(idx);
                            cursor.close();
                            return path;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {}
        }
        return songPath;
    }

    private void setLyrics(ArrayList<LrcParser.LyricLine> lyrics) {
        currentLyrics = (lyrics != null) ? lyrics : new ArrayList<LrcParser.LyricLine>();
        lastLyricIndex = LYRIC_UNSET;
        int position = 0;
        if (mediaPlayer != null) {
            try { position = mediaPlayer.getCurrentPosition(); } catch (IllegalStateException ignored) {}
        }
        LyricsActivity.replaceLyrics(currentLyrics, position);
        if (mediaPlayer != null) updateProgress();
    }

    private String getCacheLrcFileName(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) return "unknown.lrc";
        try {
            File file = new File(audioPath);
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String baseName = dot > 0 ? name.substring(0, dot) : name;
            return baseName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".lrc";
        } catch (Exception e) {
            return "unknown.lrc";
        }
    }

    private void loadLyrics(final Song song, final int durationMs) {
        setLyrics(null);
        if (song == null) return;
        final String title = song.getTitle();
        final String artist = song.getArtist();
        final String requestedPath = song.getPath();

        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<LrcParser.LyricLine> local = null;
                try {
                    File lyricsDir = new File(getFilesDir(), "lyrics_cache");
                    String safeName = getCacheLrcFileName(requestedPath);
                    File cachedLrc = new File(lyricsDir, safeName);

                    if (cachedLrc.exists()) {
                        local = LrcParser.parseLrcFileDirect(cachedLrc);
                    }
                } catch (Exception ignored) {}

                if (local == null || local.isEmpty()) {
                    String songPath = resolveFilePath(requestedPath);
                    local = LrcParser.parseLrcFile(MainActivity.this, songPath);
                }

                final ArrayList<LrcParser.LyricLine> finalLocal = local;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || !requestedPath.equals(currentSongPath)) return;
                        if (finalLocal != null && !finalLocal.isEmpty()) {
                            setLyrics(finalLocal);
                        } else {
                            fetchLyricsOnline(title, artist, durationMs, requestedPath, requestedPath);
                        }
                    }
                });
            }
        }).start();
    }

    private void openLyricsScreen() {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            showManualLyricsInputDialog();
            return;
        }
        launchLyricsActivity(currentLyrics);
    }

    private void launchLyricsActivity(ArrayList<LrcParser.LyricLine> lyrics) {
        try {
            ArrayList<String> linesText = new ArrayList<String>();
            ArrayList<Integer> linesTime = new ArrayList<Integer>();
            for (LrcParser.LyricLine line : lyrics) {
                if (line != null && line.text != null) {
                    linesText.add(line.text);
                    linesTime.add(line.timeMs);
                }
            }

            int currentPos = (mediaPlayer != null) ? mediaPlayer.getCurrentPosition() : 0;

            Intent intent = new Intent(this, LyricsActivity.class);
            intent.putStringArrayListExtra("LYRICS_TEXT", linesText);
            intent.putIntegerArrayListExtra("LYRICS_TIME", linesTime);
            intent.putExtra("CURRENT_POSITION", currentPos);
            try {
                if (mediaPlayer != null) {
                    intent.putExtra("AUDIO_SESSION_ID", mediaPlayer.getAudioSessionId());
                }
            } catch (IllegalStateException ignored) {}
            lyricsScreenRequested = true;
            startActivity(intent);
        } catch (Exception e) {
            lyricsScreenRequested = false;
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showManualLyricsInputDialog() {
        final EditText input = new EditText(this);
        input.setHint("Collez ou écrivez vos paroles ici (une ligne par phrase)...");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x77FFFFFF);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(16), dp(16), dp(16), dp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Ajouter des paroles")
            .setView(scroll)
            .setPositiveButton("Suivant (Synchroniser)", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String rawText = input.getText().toString().trim();
                    if (!rawText.isEmpty()) {
                        String[] rawLines = rawText.split("\\r?\\n");
                        ArrayList<LrcParser.LyricLine> initialLines = new ArrayList<LrcParser.LyricLine>();
                        for (String l : rawLines) {
                            if (!l.trim().isEmpty()) {
                                initialLines.add(new LrcParser.LyricLine(0, l.trim()));
                            }
                        }
                        launchLyricsActivity(initialLines);
                    } else {
                        Toast.makeText(MainActivity.this, "Le texte ne peut pas être vide", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void fetchLyricsOnline(final String title, final String artist, int durationMs,
                                   final String songPath, final String requestedPath) {
        LrcFetcher.fetchLyricsOnline(title, artist, durationMs,
            new LrcFetcher.LyricCallback() {
                @Override
                public void onLyricsFetched(final String syncedLyrics) {
                    if (syncedLyrics == null || syncedLyrics.isEmpty()) return;

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            saveLrcFileToStorage(songPath, syncedLyrics);
                        }
                    }).start();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || currentSongPath == null
                                || !currentSongPath.equals(requestedPath)) return;
                            setLyrics(LrcParser.parseLrcString(syncedLyrics));
                            toast("Paroles synchronisées chargées");
                        }
                    });
                }
            });
    }

    static void setLyricsScreenVisible(boolean visible) {
        MainActivity activity = activeActivity.get();
        if (activity == null) return;
        activity.lyricsScreenVisible = visible;
        if (visible && activity.spectrumView != null) {
            activity.spectrumView.releaseVisualizer();
        }
        if (visible && activity.isPlaying) {
            activity.startProgressUpdates();
        } else if (!visible && !activity.activityResumed) {
            activity.stopProgressUpdates();
        }
    }

    static void applyEditedLyrics(ArrayList<String> texts, ArrayList<Integer> times) {
        final MainActivity activity = activeActivity.get();
        if (activity == null || texts == null || times == null) return;

        int count = Math.min(texts.size(), times.size());
        if (count == 0) {
            activity.setLyrics(null);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int ms = Math.max(0, times.get(i));
            String text = texts.get(i) == null ? "" : texts.get(i).replace('\n', ' ').replace('\r', ' ');
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n",
                                    ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, text));
        }
        final String lrc = sb.toString();

        ArrayList<LrcParser.LyricLine> parsed = LrcParser.parseLrcString(lrc);
        if (parsed == null || parsed.isEmpty()) return;
        activity.setLyrics(parsed);

        final String path = activity.resolveFilePath(activity.currentSongPath);
        if (path != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    activity.saveLrcFileToStorage(path, lrc);
                }
            }).start();
        }
    }

    private void updateLiveLyric(int positionMs) {
        if (tvLiveLyric == null) return;

        boolean hasLyrics = currentLyrics != null && !currentLyrics.isEmpty();
        int index = -1;
        if (hasLyrics) {
            for (int i = 0; i < currentLyrics.size(); i++) {
                LrcParser.LyricLine line = currentLyrics.get(i);
                if (line != null && positionMs >= line.timeMs) {
                    index = i;
                } else {
                    break;
                }
            }
        }

        if (index == lastLyricIndex) return;
        lastLyricIndex = index;

        String text;
        if (!hasLyrics) {
            text = "♪ Pas de paroles synchronisées ♪";
        } else if (index < 0) {
            text = "♪";
        } else {
            String t = currentLyrics.get(index).text;
            text = (t != null) ? t : "";
        }
        setLyricTextAnimated(text);
    }

    private void setLyricTextAnimated(final String text) {
        if (tvLiveLyric == null || text.contentEquals(tvLiveLyric.getText())) return;

        tvLiveLyric.animate().cancel();
        tvLiveLyric.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (tvLiveLyric != null) {
                    tvLiveLyric.setText(text);
                    tvLiveLyric.animate().alpha(1f).setDuration(180).start();
                }
            }
        }).start();
    }

    private void saveLrcFileToStorage(String audioPath, String lrcContent) {
        if (lrcContent == null || lrcContent.isEmpty()) return;
        try {
            File lyricsDir = new File(getFilesDir(), "lyrics_cache");
            if (!lyricsDir.exists()) lyricsDir.mkdirs();

            String safeName = getCacheLrcFileName(audioPath);
            File lrcFile = new File(lyricsDir, safeName);

            OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(lrcFile), "UTF-8"
            );
            writer.write(lrcContent);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupEqualizer(int audioSessionId) {
        if (audioEffectManager != null) {
            audioEffectManager.attachToAudioSession(audioSessionId);
        }
    }

    private void toggleLpfMode(boolean enable) {
        if (audioEffectManager == null) {
            toast("Veuillez d'abord lancer une musique");
            return;
        }

        isLpfEnabled = enable;
        if (enable) {
            // Low-Pass Filter: boost/preserve sub and bass, attenuate highs
            short[] lpfLevels = new short[]{6, 5, 3, 1, -2, -6, -10, -12, -12, -12};
            for (int i = 0; i < AudioEffectManager.NUM_EQ_BANDS; i++) {
                savedBandLevelsBeforeLpf[i] = audioEffectManager.getBandLevel(i);
                audioEffectManager.setBandLevel(i, lpfLevels[i]);
            }
            toast("Mode LPF (Filtre Passe-Bas) Activé");
        } else {
            for (int i = 0; i < AudioEffectManager.NUM_EQ_BANDS; i++) {
                audioEffectManager.setBandLevel(i, savedBandLevelsBeforeLpf[i]);
            }
            toast("Mode LPF Désactivé");
        }
    }

    private void showEqualizerDialog() {
        if (audioEffectManager == null) {
            audioEffectManager = new AudioEffectManager(this);
        }
        if (mediaPlayer != null) {
            audioEffectManager.attachToAudioSession(mediaPlayer.getAudioSessionId());
        }

        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(18), dp(16), dp(18), dp(16));
            layout.setBackgroundColor(0xFF16161C);

            // 1. Titre & Analyseur spectral temps réel 10 bandes
            TextView tvSpectrumTitle = new TextView(this);
            tvSpectrumTitle.setText("Analyseur de Spectre FFT (10 Bandes)");
            tvSpectrumTitle.setTextColor(0xFF1DB954);
            tvSpectrumTitle.setTextSize(13f);
            tvSpectrumTitle.setPadding(0, 0, 0, dp(6));
            layout.addView(tvSpectrumTitle);

            final TenBandSpectrumBarView spectrumBarView = new TenBandSpectrumBarView(this);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(75)
            );
            barParams.setMargins(0, 0, 0, dp(4));
            spectrumBarView.setLayoutParams(barParams);
            layout.addView(spectrumBarView);

            // Connect real-time spectrum analysis data pipeline
            audioEffectManager.setOnSpectrumDataListener(new AudioEffectManager.OnSpectrumDataListener() {
                @Override
                public void onSpectrumData(float[] tenBandSpectrum, byte[] waveForm, float beatEnergy, float bassLevel) {
                    spectrumBarView.updateBands(tenBandSpectrum);
                }
            });

            // Ligne repères de fréquences ISO
            LinearLayout freqLabelsRow = new LinearLayout(this);
            freqLabelsRow.setOrientation(LinearLayout.HORIZONTAL);
            freqLabelsRow.setWeightSum(10f);
            freqLabelsRow.setPadding(0, 0, 0, dp(12));
            for (int i = 0; i < AudioEffectManager.NUM_EQ_BANDS; i++) {
                TextView tvF = new TextView(this);
                tvF.setText(AudioEffectManager.BAND_LABELS[i].replace(" ", ""));
                tvF.setTextColor(0xFF888899);
                tvF.setTextSize(9f);
                tvF.setGravity(Gravity.CENTER);
                freqLabelsRow.addView(tvF, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            layout.addView(freqLabelsRow);

            // Boutons Préréglages & Réinitialiser
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, 0, 0, dp(10));

            Button presetsBtn = new Button(this);
            presetsBtn.setText("Préréglages (10 Styles)");
            presetsBtn.setTextColor(0xFFFFFFFF);
            presetsBtn.setBackgroundColor(0xFF262632);
            presetsBtn.setTextSize(12f);

            Button resetBtn = new Button(this);
            resetBtn.setText("Réinitialiser 0 dB");
            resetBtn.setTextColor(0xFFFFAA00);
            resetBtn.setBackgroundColor(0xFF262632);
            resetBtn.setTextSize(12f);

            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
            p1.setMargins(0, 0, dp(4), 0);
            LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
            p2.setMargins(dp(4), 0, 0, 0);
            actions.addView(presetsBtn, p1);
            actions.addView(resetBtn, p2);
            layout.addView(actions);

            // Options LPF & Crossfade
            final CheckBox chkLpf = new CheckBox(this);
            chkLpf.setText("Mode LPF (Filtre Passe-Bas Club)");
            chkLpf.setTextColor(0xFFFFFFFF);
            chkLpf.setChecked(isLpfEnabled);
            layout.addView(chkLpf);

            final CheckBox chkCrossfade = new CheckBox(this);
            chkCrossfade.setText("Fondu enchaîné (Crossfade 4s)");
            chkCrossfade.setTextColor(0xFFFFFFFF);
            chkCrossfade.setChecked(isCrossfadeEnabled);
            chkCrossfade.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    isCrossfadeEnabled = isChecked;
                    prefs.edit().putBoolean(PREF_CROSSFADE, isCrossfadeEnabled).apply();
                    toast(isChecked ? "Crossfade activé" : "Crossfade désactivé");
                }
            });
            layout.addView(chkCrossfade);

            // Séparateur
            View sep1 = new View(this);
            sep1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            sep1.setBackgroundColor(0x33FFFFFF);
            layout.addView(sep1);

            // BassBoost & Virtualizer
            final TextView tvBassVal = new TextView(this);
            tvBassVal.setText("Amplificateur de Basses (BassBoost) : " + (audioEffectManager.getBassBoostStrength() / 10) + "%");
            tvBassVal.setTextColor(0xFF1DB954);
            tvBassVal.setPadding(0, dp(8), 0, dp(2));
            layout.addView(tvBassVal);

            final SeekBar barBass = new SeekBar(this);
            barBass.setMax(1000);
            barBass.setProgress(audioEffectManager.getBassBoostStrength());
            barBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        audioEffectManager.setBassBoostStrength((short) progress);
                        tvBassVal.setText("Amplificateur de Basses (BassBoost) : " + (progress / 10) + "%");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(barBass);

            final TextView tvVirtVal = new TextView(this);
            tvVirtVal.setText("Effet Surround 3D (Virtualizer) : " + (audioEffectManager.getVirtualizerStrength() / 10) + "%");
            tvVirtVal.setTextColor(0xFF1DB954);
            tvVirtVal.setPadding(0, dp(8), 0, dp(2));
            layout.addView(tvVirtVal);

            final SeekBar barVirt = new SeekBar(this);
            barVirt.setMax(1000);
            barVirt.setProgress(audioEffectManager.getVirtualizerStrength());
            barVirt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        audioEffectManager.setVirtualizerStrength((short) progress);
                        tvVirtVal.setText("Effet Surround 3D (Virtualizer) : " + (progress / 10) + "%");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(barVirt);

            View sep2 = new View(this);
            sep2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            sep2.setBackgroundColor(0x33FFFFFF);
            layout.addView(sep2);

            // 10 Curseurs d'Égaliseur (avec étiquettes ISO et dB)
            final SeekBar[] eqBars = new SeekBar[AudioEffectManager.NUM_EQ_BANDS];
            final TextView[] eqLabels = new TextView[AudioEffectManager.NUM_EQ_BANDS];

            for (int i = 0; i < AudioEffectManager.NUM_EQ_BANDS; i++) {
                final int band = i;
                final TextView label = new TextView(this);
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(13f);
                label.setPadding(0, dp(8), 0, dp(1));
                short currentDb = audioEffectManager.getBandLevel(band);
                label.setText(AudioEffectManager.BAND_LABELS[band] + "  :  " + String.format(Locale.US, "%+d dB", currentDb));
                layout.addView(label);
                eqLabels[i] = label;

                final SeekBar bar = new SeekBar(this);
                // Plage : -12 dB à +12 dB => total 24 étapes, point neutre = 12
                bar.setMax(24);
                bar.setProgress(currentDb + 12);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            short levelDb = (short) (progress - 12);
                            audioEffectManager.setBandLevel(band, levelDb);
                            label.setText(AudioEffectManager.BAND_LABELS[band] + "  :  " + String.format(Locale.US, "%+d dB", levelDb));
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
                layout.addView(bar);
                eqBars[i] = bar;
            }

            // Gestionnaires des préréglages
            presetsBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String[] presetNames = new String[AudioEffectManager.PRESETS.length];
                    for (int p = 0; p < AudioEffectManager.PRESETS.length; p++) {
                        presetNames[p] = AudioEffectManager.PRESETS[p].name;
                    }

                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Choisir un style audio")
                        .setItems(presetNames, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                audioEffectManager.applyPreset(AudioEffectManager.PRESETS[which]);
                                for (int b = 0; b < AudioEffectManager.NUM_EQ_BANDS; b++) {
                                    short lvl = audioEffectManager.getBandLevel(b);
                                    eqBars[b].setProgress(lvl + 12);
                                    eqLabels[b].setText(AudioEffectManager.BAND_LABELS[b] + "  :  " + String.format(Locale.US, "%+d dB", lvl));
                                }
                                toast("Préréglage appliqué : " + presetNames[which]);
                            }
                        })
                        .show();
                }
            });

            resetBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    audioEffectManager.resetBands();
                    for (int b = 0; b < AudioEffectManager.NUM_EQ_BANDS; b++) {
                        eqBars[b].setProgress(12);
                        eqLabels[b].setText(AudioEffectManager.BAND_LABELS[b] + "  :  0 dB");
                    }
                    audioEffectManager.setBassBoostStrength((short) 0);
                    audioEffectManager.setVirtualizerStrength((short) 0);
                    barBass.setProgress(0);
                    barVirt.setProgress(0);
                    tvBassVal.setText("Amplificateur de Basses (BassBoost) : 0%");
                    tvVirtVal.setText("Effet Surround 3D (Virtualizer) : 0%");
                    toast("Égaliseur réinitialisé à 0 dB");
                }
            });

            chkLpf.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    toggleLpfMode(isChecked);
                    for (int b = 0; b < AudioEffectManager.NUM_EQ_BANDS; b++) {
                        short lvl = audioEffectManager.getBandLevel(b);
                        eqBars[b].setProgress(lvl + 12);
                        eqLabels[b].setText(AudioEffectManager.BAND_LABELS[b] + "  :  " + String.format(Locale.US, "%+d dB", lvl));
                    }
                }
            });

            ScrollView scroll = new ScrollView(this);
            scroll.addView(layout);

            new AlertDialog.Builder(this)
                .setTitle("Égaliseur 10 Bandes & DSP")
                .setView(scroll)
                .setPositiveButton("Fermer", null)
                .show();

        } catch (Exception e) {
            toast("Erreur d'ouverture de l'égaliseur : " + e.getMessage());
        }
    }

    public static String getCurrentSongPathStatic() {
        MainActivity activity = activeActivity.get();
        if (activity != null) {
            return activity.currentSongPath;
        }
        return null;
    }

    public static int getCurrentPositionStatic() {
        MainActivity activity = activeActivity.get();
        if (activity != null && activity.mediaPlayer != null) {
            try {
                return activity.mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static int getDurationStatic() {
        MainActivity activity = activeActivity.get();
        if (activity != null && activity.mediaPlayer != null) {
            try {
                return activity.mediaPlayer.getDuration();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static boolean isPlayingStatic() {
        MainActivity activity = activeActivity.get();
        if (activity != null && activity.mediaPlayer != null) {
            try {
                return activity.mediaPlayer.isPlaying();
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static void togglePlayPauseStatic() {
        MainActivity activity = activeActivity.get();
        if (activity != null) {
            activity.togglePlayPause();
        }
    }

    public static void seekToStatic(int posMs) {
        MainActivity activity = activeActivity.get();
        if (activity != null && activity.mediaPlayer != null) {
            try {
                activity.mediaPlayer.seekTo(posMs);
            } catch (Exception ignored) {}
        }
    }

    public static void playSongFromPathStatic(final Context context, final String filePath, final String title) {
        if (filePath == null) return;
        final MainActivity activity = activeActivity.get();
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.playSongFromPathInternal(filePath, title);
                }
            });
        } else if (context != null) {
            Intent intent = new Intent(context, MainActivity.class);
            intent.putExtra("PLAY_FILE_PATH", filePath);
            intent.putExtra("PLAY_FILE_TITLE", title);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        }
    }
}
