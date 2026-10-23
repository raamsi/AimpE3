package com.ramsi.aimpe3;

import android.Manifest;
import android.app.Activity;
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
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
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


import android.media.audiofx.Equalizer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Virtualizer;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.TextView;
import android.app.AlertDialog;
import android.content.DialogInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.ArrayList;


public class MainActivity extends Activity {
    private static WeakReference<MainActivity> activeActivity =
    new WeakReference<MainActivity>(null);

    // ------------------------------------------------------------------
    // Constantes
    // ------------------------------------------------------------------
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int UI_UPDATE_INTERVAL_MS = 300;   // Fluidité SeekBar + paroles
    private static final int LYRICS_UPDATE_INTERVAL_MS = 100;  // sync précise des paroles
    private static final int SEEK_STEP_MS = 10000;          // Avance / retour rapide
    private static final int RESTART_THRESHOLD_MS = 3000;   // "Précédent" relance le morceau après 3 s
    private static final int LYRIC_UNSET = -2;

    private static final int COLOR_ACTIVE = 0xFF1DB954;
    private static final int COLOR_INACTIVE = 0xFFAAAAAA;

    private static final String PREFS_NAME = "AimpE3Settings";
    private static final String PREF_REPEAT = "repeat";
    private static final String PREF_SHUFFLE = "shuffle";
    private static final String PREF_EQ_LEVELS = "eq_levels";
    
   // private Equalizer equalizer;
    private android.media.audiofx.BassBoost bassBoost;
    private android.media.audiofx.Virtualizer virtualizer;
    
    
    // Déclaration de la liste de lecture et de l'index actuel
    private ArrayList<Song> playlist = new ArrayList<>();
    private int currentIndex = 0;
    

    // ------------------------------------------------------------------
    // Vues
    // ------------------------------------------------------------------
    private LinearLayout layoutPlayer, layoutPlaylist;
    private ImageButton btnNavList, btnNavEq;
    private ImageButton buttonPrev, buttonNext, buttonRewind, buttonForward;
    private ImageButton btnToolRepeat, btnToolShuffle, btnToolEq, btnLyrics;
    private Button buttonPlay;

    private TextView tvMainTitle, tvMainArtist, tvLiveLyric, tvCurrentTime, tvTotalDuration, tvAudioQuality;
    private RgbBorderImageView imgMainCover;
    private ImageView imgBlurredBackground;
    private SpectrumView spectrumView;
    private SeekBar seekbar1;
    private ListView listview1;
    
    // --- Variables de Crossfade (HORS de onCreate) ---
    private MediaPlayer mediaPlayer;
    private MediaPlayer nextMediaPlayer;
    private boolean isCrossfadeEnabled = true;
    private int crossfadeDurationMs = 6000; // 3 secondes
    private boolean isNextSongTriggered = false;


    private ImageButton btnSearchMain;

    // ------------------------------------------------------------------
    // Données et lecture
    // ------------------------------------------------------------------
    private ArrayList<Song> songList;
    private SongAdapter songAdapter;
    private final HashMap<String, String> uriToFilePath = new HashMap<String, String>();

    // private MediaPlayer mediaPlayer;
    private Equalizer equalizer;
    private AudioManager audioManager;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

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
    private int currentSongIndex = 0;
    private String currentSongPath = null;
    private int metadataRequestId = 0;
    private int playbackRequestId = 0;

    // Paroles synchronisées (.lrc)
    private ArrayList<LrcParser.LyricLine> currentLyrics = new ArrayList<LrcParser.LyricLine>();
    private int lastLyricIndex = LYRIC_UNSET;

    // ------------------------------------------------------------------
    // Boucle de mise à jour de l'interface (un seul Runnable, jamais dupliqué)
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // Focus audio et débranchement du casque
    // ------------------------------------------------------------------
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

    // ==================================================================
    // Cycle de vie
    // ==================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeActivity = new WeakReference<MainActivity>(this);
        setupWindow();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Lancer le scan automatique des audios à chaque démarrage
        rescanAudioFiles();

        bindViews();
        setupList();
        setupControls();
        setupSeekBar();
        restoreToggleStates();

        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        imgMainCover.setAnimating(false);

        checkPermissions();
        
        // À l'intérieur de ton Runnable/Handler d'actualisation de la SeekBar :
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            int currentPos = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();

            // Déclencher le crossfade s'il reste moins de 3 secondes
            if (isCrossfadeEnabled && (duration - currentPos <= crossfadeDurationMs) && !isNextSongTriggered) {
                isNextSongTriggered = true;
                playNextSong(); // Appel de ta fonction pour passer au morceau suivant
            }
        }
        


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
        if (mediaPlayer != null) {
            updateProgress();
            if (isPlaying) startProgressUpdates();
        }
    }



    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        if (!lyricsScreenRequested && !lyricsScreenVisible) stopProgressUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        metadataRequestId++; // invalide les chargements en cours
        playbackRequestId++;
        if (activeActivity.get() == this) {
            activeActivity = new WeakReference<MainActivity>(null);
        }
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);

        if (spectrumView != null) spectrumView.releaseVisualizer();
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        releasePlayer();
        
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        if (bassBoost != null) {
            bassBoost.release();
            bassBoost = null;
        }
        if (virtualizer != null) {
            virtualizer.release();
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



    // ==================================================================
    // Initialisation
    // ==================================================================
    private void setupWindow() {
        // Barre de statut transparente & affichage immersif Edge-to-Edge
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

        // Liaison du bouton XML avec le code Java
        btnSearchMain = findViewById(R.id.btnSearchMain);
        // Écouteur de clic pour ouvrir SearchActivity
        // Écouteur de clic sécurisé pour ouvrir SearchActivity
        btnSearchMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent intent = new Intent(MainActivity.this, SearchActivity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        // Si un problème survient, l'application ne quitte plus : 
                        // elle affiche l'erreur exacte à l'écran dans un Toast !
                        Toast.makeText(MainActivity.this, "Erreur au clic : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });


        EditText etSearchMusic = (EditText) findViewById(R.id.etSearchMusic);
        final ListView listView1 = (ListView) findViewById(R.id.listview1);

        if (etSearchMusic != null && listView1 != null) {
            etSearchMusic.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                        // Rien à faire ici
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (listView1.getAdapter() != null) {
                            // Si c'est un ArrayAdapter standard
                            if (listView1.getAdapter() instanceof android.widget.ArrayAdapter) {
                                ((android.widget.ArrayAdapter<?>) listView1.getAdapter()).getFilter().filter(s);
                            }
                        }
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        // Rien à faire ici
                    }
                });
        }




        btnLyrics = (ImageButton) findViewById(R.id.btnLyrics);
        btnNavList = (ImageButton) findViewById(R.id.btn_nav_list);
        btnNavEq = (ImageButton) findViewById(R.id.btn_nav_eq);

        buttonPlay = (Button) findViewById(R.id.buttonPlay);
        buttonPrev = (ImageButton) findViewById(R.id.buttonPrev);
        buttonNext = (ImageButton) findViewById(R.id.buttonNext);
        buttonRewind = (ImageButton) findViewById(R.id.buttonRewind);
        buttonForward = (ImageButton) findViewById(R.id.buttonForward);

        btnToolRepeat = (ImageButton) findViewById(R.id.btnToolRepeat);
        btnToolShuffle = (ImageButton) findViewById(R.id.btnToolShuffle);
        btnToolEq = (ImageButton) findViewById(R.id.btnToolEq);

        tvMainTitle = (TextView) findViewById(R.id.tvMainTitle);
        tvMainArtist = (TextView) findViewById(R.id.tvMainArtist);
        tvLiveLyric = (TextView) findViewById(R.id.tvLiveLyric);
        tvCurrentTime = (TextView) findViewById(R.id.tvCurrentTime);
        tvTotalDuration = (TextView) findViewById(R.id.tvTotalDuration);
        tvAudioQuality = (TextView) findViewById(R.id.tvAudioQuality);

        // Nécessaire pour que le texte trop long défile (ellipsize="marquee")
        tvMainTitle.setSelected(true);
        tvMainArtist.setSelected(true);

        imgMainCover = (RgbBorderImageView) findViewById(R.id.imgMainCover);
        imgMainCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imgBlurredBackground = (ImageView) findViewById(R.id.imgBlurredBackground);
        imgBlurredBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);

        spectrumView = (SpectrumView) findViewById(R.id.spectrumView);
        seekbar1 = (SeekBar) findViewById(R.id.seekbar1);
        listview1 = (ListView) findViewById(R.id.listview1);
    }

    private void setupList() {
        songList = new ArrayList<Song>();
        songAdapter = new SongAdapter(this, songList);
        listview1.setAdapter(songAdapter);

        listview1.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    playSongAt(position);
                    showPlaylist(false);
                }
            });
    }




    private void setupControls() {
        buttonPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlayPause();
                }
            });

        buttonNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playNext();
                }
            });

        buttonPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playPrevious();
                }
            });

        buttonForward.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seekBy(SEEK_STEP_MS);
                }
            });

        buttonRewind.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seekBy(-SEEK_STEP_MS);
                }
            });

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

        btnToolShuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isShuffleEnabled = !isShuffleEnabled;
                    prefs.edit().putBoolean(PREF_SHUFFLE, isShuffleEnabled).apply();
                    applyToggleColor(btnToolShuffle, isShuffleEnabled);
                    toast(isShuffleEnabled ? "Lecture aléatoire activée" : "Lecture aléatoire désactivée");
                }
            });

        btnLyrics.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLyricsScreen();
                }
            });

        View.OnClickListener eqClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEqualizerDialog();
            }
        };
        if (btnToolEq != null) btnToolEq.setOnClickListener(eqClickListener);
        if (btnNavEq != null) btnNavEq.setOnClickListener(eqClickListener);

        btnNavList.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPlaylist(layoutPlaylist.getVisibility() != View.VISIBLE);
                }
            });



    }
    
    

    private void setupSeekBar() {
        seekbar1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // Pendant le glissement : on affiche seulement le temps, sans saccader le son
                    if (fromUser) tvCurrentTime.setText(formatTime(progress));
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

    private void restoreToggleStates() {
        isRepeatEnabled = prefs.getBoolean(PREF_REPEAT, false);
        isShuffleEnabled = prefs.getBoolean(PREF_SHUFFLE, false);
        applyToggleColor(btnToolRepeat, isRepeatEnabled);
        applyToggleColor(btnToolShuffle, isShuffleEnabled);
    }

    private void applyToggleColor(ImageButton button, boolean active) {
        button.setColorFilter(active ? COLOR_ACTIVE : COLOR_INACTIVE);
    }

    private void showPlaylist(boolean show) {
        layoutPlaylist.setVisibility(show ? View.VISIBLE : View.GONE);
        layoutPlayer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================================================================
    // Permissions et chargement de la bibliothèque
    // ==================================================================
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
            needed.add(Manifest.permission.RECORD_AUDIO); // requis pour le spectre (Visualizer)
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
            if (hasStoragePermission()) {
                loadMusicFiles();
            } else {
                toast("Autorisez l'accès à la musique pour afficher vos morceaux");
            }
        }
    }

    /** Charge la bibliothèque en arrière-plan pour ne pas figer l'écran. */
    private void loadMusicFiles() {
        new Thread(new Runnable() {
                @Override
                public void run() {
                    final ArrayList<Song> loaded = new ArrayList<Song>();
                    final HashMap<String, String> paths = new HashMap<String, String>();
                    queryMusic(loaded, paths);

                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                uriToFilePath.clear();
                                uriToFilePath.putAll(paths);
                                songList.clear();
                                songList.addAll(loaded);
                                songAdapter.notifyDataSetChanged();
                                if (loaded.isEmpty()) toast("Aucune musique trouvée");
                            }
                        });
                }
            }).start();
    }

    private void rescanAudioFiles() {
        // Cible le dossier Musique public de l'appareil
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);

        if (musicDir != null && musicDir.exists()) {
            // Déclenche le scan de tout le dossier pour avertir le système Android
            MediaScannerConnection.scanFile(
                this,
                new String[]{musicDir.getAbsolutePath()},
                null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        // Le scan est terminé, les musiques sont prêtes à être lues
                    }
                }
            );
        }}

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

        // Fichiers non indexés par Android (dédoublonnés grâce au chemin réel)
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
    
    // --- Méthode de lecture avec Crossfade (HORS de onCreate) ---
    private void playSongWithCrossfade(final String path) {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying() && isCrossfadeEnabled) {
                final MediaPlayer oldPlayer = mediaPlayer;

                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(path);
                mediaPlayer.prepare();
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.start();

                startCrossfadeEffect(oldPlayer, mediaPlayer);
            } else {
                if (mediaPlayer != null) {
                    mediaPlayer.release();
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(path);
                mediaPlayer.prepare();
                mediaPlayer.setVolume(1.0f, 1.0f);
                mediaPlayer.start();
            }

            if (mediaPlayer != null) {
                setupEqualizer(mediaPlayer.getAudioSessionId());
            }
        } catch (Exception e) {
            e.printStackTrace();
            toast("Erreur lors de la lecture du fichier");
        }
    }

    // --- Animation progressive du fondu (HORS de onCreate) ---
    private void startCrossfadeEffect(final MediaPlayer outPlayer, final MediaPlayer inPlayer) {
        final int steps = 50; // Plus d'étapes pour plus de progressivité (50 au lieu de 30)
        final long interval = crossfadeDurationMs / steps;

        new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i <= steps; i++) {
                            float volumeOut = 1.0f - ((float) i / steps);
                            float volumeIn = (float) i / steps;

                            if (outPlayer.isPlaying()) {
                                outPlayer.setVolume(volumeOut, volumeOut);
                            }
                            inPlayer.setVolume(volumeIn, volumeIn);

                            Thread.sleep(interval);
                        }

                        outPlayer.stop();
                        outPlayer.release();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
    }
    
    

    // ==================================================================
    // Lecture
    // ==================================================================
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

        // Comportement standard : au-delà de 3 s, "précédent" relance le morceau
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
            buttonPlay.setText(startWhenPrepared ? "⏸" : "▶");
        } else if (isPlaying) {
            pausePlayback();
        } else {
            resumePlayback();
        }
    }
    
    // Méthode appelée lors de l'appui sur le bouton NEXT ou automatique
    private void playNextSong() {
        try {
            if (playlist == null || playlist.isEmpty()) return;

            // 1. Calculer l'index de la chanson suivante
            currentIndex = (currentIndex + 1) % playlist.size();
            final Song nextSong = playlist.get(currentIndex);

            if (nextSong != null && currentSongPath != null) {
                // Si le Crossfade est activé et qu'un morceau joue déjà
                if (isCrossfadeEnabled && mediaPlayer != null && mediaPlayer.isPlaying()) {

                    final MediaPlayer oldPlayer = mediaPlayer;
                    currentSongPath = nextSong.getPath();

                    // Créer et préparer immédiatement le nouveau MediaPlayer
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(currentSongPath);
                    mediaPlayer.prepare();
                    mediaPlayer.setVolume(0f, 0f); // Démarre en silence
                    mediaPlayer.start();

                    // Mettre à jour l'égaliseur et les paroles pour la nouvelle chanson
                    setupEqualizer(mediaPlayer.getAudioSessionId());
                    loadLyrics(nextSong, mediaPlayer.getDuration());

                    // Lancer la transition croisée
                    startCrossfadeEffect(oldPlayer, mediaPlayer);

                } else {
                    // Lecture standard sans Crossfade
                    currentSongPath = nextSong.getPath();
                    if (mediaPlayer != null) {
                        mediaPlayer.release();
                    }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(currentSongPath);
                    mediaPlayer.prepare();
                    mediaPlayer.setVolume(1.0f, 1.0f);
                    mediaPlayer.start();

                    setupEqualizer(mediaPlayer.getAudioSessionId());
                    loadLyrics(nextSong, mediaPlayer.getDuration());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            toast("Erreur lors du passage à la chanson suivante");
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
        buttonPlay.setText(playing ? "⏸" : "▶");
        imgMainCover.setAnimating(playing); // la bordure RGB ne s'anime que pendant la lecture
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
        releasePlayer();
        setPlayingState(false);

        // 1. Mettre à jour immédiatement le chemin et vider l'écran des paroles
        final String path = song.getPath();
        currentSongPath = path;
        setLyrics(null); 
        updateLiveLyric(0);

        final MediaPlayer mp = new MediaPlayer();
        mediaPlayer = mp;
        isPreparing = true;
        startWhenPrepared = true;
        buttonPlay.setText("⏸");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                                      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                      .setUsage(AudioAttributes.USAGE_MEDIA)
                                      .build());
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }

            if (path.startsWith("content://")) {
                mp.setDataSource(this, Uri.parse(path));
            } else {
                mp.setDataSource(path);
            }

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

                        tvMainTitle.setText(song.getTitle());
                        tvMainArtist.setText(song.getArtist());
                        int duration = player.getDuration();
                        seekbar1.setMax(duration);
                        seekbar1.setProgress(0);
                        tvCurrentTime.setText(formatTime(0));
                        tvTotalDuration.setText(formatTime(duration));

                        int sessionId = player.getAudioSessionId();
                        if (spectrumView != null && !lyricsScreenVisible) {
                            spectrumView.linkMediaPlayer(sessionId);
                        }
                        LyricsActivity.updateAudioSession(sessionId);
                        setupEqualizer(sessionId);
                        loadTrackMetadata(path);

                        // 2. Charger les paroles (cherche d'abord le .lrc local dans le dossier, puis en ligne)
                        loadLyrics(song, duration);
                    }
                });
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
                            toast("Erreur de lecture audio");
                        }
                        return true;
                    }
                });

            mp.prepareAsync();
        } catch (Exception e) {
            isPreparing = false;
            if (mediaPlayer == mp) releasePlayer();
            else mp.release();
            setPlayingState(false);
            toast("Impossible de lire ce morceau");
            return;
        }
    }
    
    
    private boolean isLpfEnabled = false;
    private final short[] savedBandLevelsBeforeLpf = new short[10]; // Pour mémoriser les réglages

    private void toggleLpfMode(boolean enable) {
        if (equalizer == null) {
            toast("Veuillez d'abord lancer une musique");
            return;
        }

        isLpfEnabled = enable;
        short bandCount = equalizer.getNumberOfBands();
        short minLevel = equalizer.getBandLevelRange()[0];

        try {
            if (enable) {
                // Sauvegarder les niveaux actuels et appliquer le filtre LPF
                for (short i = 0; i < bandCount; i++) {
                    savedBandLevelsBeforeLpf[i] = equalizer.getBandLevel(i);

                    // Si c'est une bande haute (les 40% ou 50% derniers), on l'atténue au minimum
                    if (i >= bandCount / 2) {
                        equalizer.setBandLevel(i, minLevel); // Coupe les aigus
                    } else {
                        // On laisse ou booste légèrement les basses et médiums
                        short current = equalizer.getBandLevel(i);
                        equalizer.setBandLevel(i, (short) Math.max(current, 0));
                    }
                }
                toast("Mode LPF (Filtre Passe-Bas) Activé");
            } else {
                // Restaurer les niveaux d'origine
                for (short i = 0; i < bandCount; i++) {
                    equalizer.setBandLevel(i, savedBandLevelsBeforeLpf[i]);
                }
                toast("Mode LPF Désactivé");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    

    // ==================================================================
    // Focus audio
    // ==================================================================
    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        return audioManager.requestAudioFocus(focusListener,
                                              AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
    }

    // ==================================================================
    // Barre de progression
    // ==================================================================
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
        try {
            position = mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            return;
        }

        if (!isUserSeeking) {
            seekbar1.setProgress(position);
            tvCurrentTime.setText(formatTime(position));
        }
        if (activityResumed) updateLiveLyric(position);
        LyricsActivity.updatePlaybackPosition(position);
    }

    private String formatTime(int ms) {
        int totalSeconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ==================================================================
    // Pochette, fond flouté et qualité audio (chargés hors du thread UI)
    // ==================================================================
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
                        if (path.startsWith("content://")) {
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
                                // Ignorer si l'utilisateur a déjà changé de morceau
                                if (isFinishing() || requestId != metadataRequestId) return;
                                applyTrackMetadata(fArt, fBlur, fQuality);
                            }
                        });
                }
            }).start();
    }

    private void applyTrackMetadata(Bitmap art, Bitmap blurred, String quality) {
        tvAudioQuality.setText(quality);
        imgMainCover.setBackgroundColor(Color.TRANSPARENT);

        if (art != null) {
            imgMainCover.setImageBitmap(art);
            crossfadeBackground(blurred);
        } else {
            imgMainCover.setImageDrawable(new ColorDrawable(0xFF333333));
            crossfadeBackground(null);
        }
        animateCoverIn();
    }

    private void animateCoverIn() {
        imgMainCover.setAlpha(0f);
        imgMainCover.setScaleX(0.92f);
        imgMainCover.setScaleY(0.92f);
        imgMainCover.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(350)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    /** Fondu enchaîné entre l'ancien et le nouveau fond flouté. */
    private void crossfadeBackground(Bitmap blurred) {
        if (blurred == null) {
            imgBlurredBackground.setImageDrawable(null);
            return;
        }

        Drawable old = imgBlurredBackground.getDrawable();
        // Évite d'empiler les TransitionDrawable (fuite mémoire)
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

    /** Vrai flou : miniature + flou de boîte, puis l'ImageView l'agrandit en lissant. */
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

    // ==================================================================
    // Paroles
    // ==================================================================
    private String resolveFilePath(String songPath) {
        if (songPath != null && songPath.startsWith("content://")) {
            String real = uriToFilePath.get(songPath);
            if (real != null) return real;
            // Sécurité : récupérer le chemin réel via le ContentResolver si absent de la map
            try {
                android.database.Cursor cursor = getContentResolver().query(
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
                        // 1. Vérifier dans le cache interne de l'app (spécial Android 13) basé sur le fichier audio
                        File lyricsDir = new File(getFilesDir(), "lyrics_cache");
                        String safeName = getCacheLrcFileName(requestedPath);
                        File cachedLrc = new File(lyricsDir, safeName);

                        if (cachedLrc.exists()) {
                            local = LrcParser.parseLrcFileDirect(cachedLrc);
                        }
                    } catch (Exception ignored) {}

                    // 2. Si non trouvé dans le cache interne, essayer la méthode classique
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
                                    // 3. Sinon, chercher en ligne
                                    fetchLyricsOnline(title, artist, durationMs, requestedPath, requestedPath);
                                }
                            }
                        });
                }
            }).start();
    }
    
    
    
    
    private void openLyricsScreen() {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            // Aucune parole trouvée ni en local ni en ligne : afficher un AlertDialog avec un EditText pour coller les paroles
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
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                        // Découper le texte ligne par ligne pour l'envoyer à l'éditeur interactif
                        String[] rawLines = rawText.split("\\r?\\n");
                        ArrayList<LrcParser.LyricLine> initialLines = new ArrayList<LrcParser.LyricLine>();
                        for (String l : rawLines) {
                            if (!l.trim().isEmpty()) {
                                initialLines.add(new LrcParser.LyricLine(0, l.trim()));
                            }
                        }
                        // Lancer l'activité de synchronisation interactive
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

                    // Sauvegarde du .lrc hors du thread UI
                    new Thread(new Runnable() {
                            @Override
                            public void run() {
                                saveLrcFileToStorage(songPath, syncedLyrics);
                            }
                        }).start();

                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Le morceau a changé entre-temps : on jette ces paroles
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

    /** Appelée par LyricsActivity après une modification dans l'éditeur. */
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
                    break; // liste triée : inutile d'aller plus loin
                }
            }
        }

        if (index == lastLyricIndex) return; // rien n'a changé
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
        if (text.contentEquals(tvLiveLyric.getText())) return;

        tvLiveLyric.animate().cancel();
        tvLiveLyric.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
                @Override
                public void run() {
                    tvLiveLyric.setText(text);
                    tvLiveLyric.animate().alpha(1f).setDuration(180).start();
                }
            }).start();
    }

    /** Écrit le .lrc à côté du fichier audio (UTF-8). Appelé hors du thread UI. */
    /** Écrit le .lrc à côté du fichier audio (UTF-8) compatible Android 13. */
    /** Écrit le .lrc dans le stockage interne de l'application (100% compatible Android 13). */
    private void saveLrcFileToStorage(String audioPath, String lrcContent) {
        if (lrcContent == null || lrcContent.isEmpty()) return;

        try {
            File lyricsDir = new File(getFilesDir(), "lyrics_cache");
            if (!lyricsDir.exists()) {
                lyricsDir.mkdirs();
            }

            // Nom de fichier unique et stable basé sur le chemin réel de l'audio
            String safeName = getCacheLrcFileName(audioPath);
            File lrcFile = new File(lyricsDir, safeName);

            java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(lrcFile), "UTF-8"
            );
            writer.write(lrcContent);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    

    // ==================================================================
    // Égaliseur
    // ==================================================================
    private void setupEqualizer(int audioSessionId) {
        try {
            if (equalizer != null) {
                equalizer.release();
                equalizer = null;
            }
            if (bassBoost != null) {
                bassBoost.release();
                bassBoost = null;
            }
            if (virtualizer != null) {
                virtualizer.release();
                virtualizer = null;
            }

            // 1. Égaliseur
            equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(true);
            restoreEqualizerLevels();

            // 2. BassBoost (Amplificateur de basses)
            bassBoost = new android.media.audiofx.BassBoost(0, audioSessionId);
            bassBoost.setEnabled(true);

            // 3. Virtualizer (Effet Surround 3D)
            virtualizer = new android.media.audiofx.Virtualizer(0, audioSessionId);
            virtualizer.setEnabled(true);

        } catch (Exception e) {
            equalizer = null;
            bassBoost = null;
            virtualizer = null;
        }
    }
    

    /** Réapplique les réglages sauvegardés : ils survivent aux changements de morceau et au redémarrage. */
    private void restoreEqualizerLevels() {
        String saved = prefs.getString(PREF_EQ_LEVELS, null);
        if (saved == null || equalizer == null) return;

        String[] parts = saved.split(",");
        short bands = equalizer.getNumberOfBands();
        if (parts.length != bands) return;

        short min = equalizer.getBandLevelRange()[0];
        short max = equalizer.getBandLevelRange()[1];
        for (short i = 0; i < bands; i++) {
            try {
                int level = Integer.parseInt(parts[i].trim());
                level = Math.max(min, Math.min(max, level));
                equalizer.setBandLevel(i, (short) level);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void saveEqualizerLevels() {
        if (equalizer == null) return;
        StringBuilder sb = new StringBuilder();
        for (short i = 0; i < equalizer.getNumberOfBands(); i++) {
            if (i > 0) sb.append(',');
            sb.append(equalizer.getBandLevel(i));
        }
        prefs.edit().putString(PREF_EQ_LEVELS, sb.toString()).apply();
    }

    private String eqLabel(short band, short level) {
        int hz = equalizer.getCenterFreq(band) / 1000;
        String freq = (hz >= 1000)
            ? String.format(Locale.US, "%.1f kHz", hz / 1000f)
            : hz + " Hz";
        return freq + "    " + String.format(Locale.US, "%+.1f dB", level / 100f);
    }

    private void syncEqualizerUi(SeekBar[] bars, TextView[] labels, short minLevel) {
        if (equalizer == null) return;
        for (short i = 0; i < bars.length; i++) {
            short level = equalizer.getBandLevel(i);
            bars[i].setProgress(level - minLevel);
            labels[i].setText(eqLabel(i, level));
        }
    }

    private void showEqualizerDialog() {
        if (equalizer == null && mediaPlayer != null) {
            setupEqualizer(mediaPlayer.getAudioSessionId());
        }
        if (equalizer == null) {
            toast("Effets audio non disponibles (lancez une musique)");
            return;
        }

        try {
            final short bandCount = equalizer.getNumberOfBands();
            final short minLevel = equalizer.getBandLevelRange()[0];
            final short maxLevel = equalizer.getBandLevelRange()[1];
            final SeekBar[] bars = new SeekBar[bandCount];
            final TextView[] labels = new TextView[bandCount];

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(20), dp(16), dp(20), dp(16));
            layout.setBackgroundColor(0xFF1E1E1E);

            // --- 1. Option Mode LPF (Low-Pass Filter) en haut ---
            final android.widget.CheckBox chkLpf = new android.widget.CheckBox(this);
            chkLpf.setText("Activer le mode LPF (Filtre Passe-Bas)");
            chkLpf.setTextColor(0xFFFFFFFF);
            chkLpf.setChecked(isLpfEnabled);
            LinearLayout.LayoutParams chkParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            chkParams.setMargins(0, dp(5), 0, dp(5));
            chkLpf.setLayoutParams(chkParams);
            chkLpf.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        toggleLpfMode(isChecked);
                    }
                });
            layout.addView(chkLpf);

            // --- Option Fondu enchaîné (Crossfade) ---
            final android.widget.CheckBox chkCrossfade = new android.widget.CheckBox(this);
            chkCrossfade.setText("Activer le fondu enchaîné (Crossfade)");
            chkCrossfade.setTextColor(0xFFFFFFFF);
            chkCrossfade.setChecked(isCrossfadeEnabled);

            LinearLayout.LayoutParams crossfadeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            crossfadeParams.setMargins(0, dp(5), 0, dp(10));
            chkCrossfade.setLayoutParams(crossfadeParams);

            chkCrossfade.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        isCrossfadeEnabled = isChecked;
                        toast(isChecked ? "Crossfade activé" : "Crossfade désactivé");
                    }
                });
            layout.addView(chkCrossfade);
            

            // Séparateur visuel
            View sepTop = new View(this);
            sepTop.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            sepTop.setBackgroundColor(0x44FFFFFF);
            layout.addView(sepTop);

            // --- Section BassBoost & Virtualizer ---
            TextView tvBassTitle = new TextView(this);
            tvBassTitle.setText("Amplificateur de Basses (BassBoost)");
            tvBassTitle.setTextColor(0xFF1DB954);
            tvBassTitle.setPadding(0, dp(10), 0, dp(2));
            layout.addView(tvBassTitle);

            final SeekBar barBass = new SeekBar(this);
            barBass.setMax(1000);
            if (bassBoost != null && bassBoost.getStrengthSupported()) {
                barBass.setProgress(bassBoost.getRoundedStrength());
            }
            barBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && bassBoost != null) {
                            bassBoost.setStrength((short) progress);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            layout.addView(barBass);

            TextView tvVirtTitle = new TextView(this);
            tvVirtTitle.setText("Effet Surround 3D (Virtualizer)");
            tvVirtTitle.setTextColor(0xFF1DB954);
            tvVirtTitle.setPadding(0, dp(10), 0, dp(2));
            layout.addView(tvVirtTitle);

            final SeekBar barVirt = new SeekBar(this);
            barVirt.setMax(1000);
            if (virtualizer != null && virtualizer.getStrengthSupported()) {
                barVirt.setProgress(virtualizer.getRoundedStrength());
            }
            barVirt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && virtualizer != null) {
                            virtualizer.setStrength((short) progress);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            layout.addView(barVirt);
            
            

            // Séparateur visuel
            View sep = new View(this);
            sep.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            sep.setBackgroundColor(0x44FFFFFF);
            LinearLayout.LayoutParams sepLp = (LinearLayout.LayoutParams) sep.getLayoutParams();
            sepLp.setMargins(0, dp(14), 0, dp(10));
            sep.setLayoutParams(sepLp);
            layout.addView(sep);

            // Boutons Préréglages / Réinitialiser
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button presetsBtn = new Button(this);
            presetsBtn.setText("Préréglages");
            Button resetBtn = new Button(this);
            resetBtn.setText("Réinitialiser");
            actions.addView(presetsBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actions.addView(resetBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            layout.addView(actions);

            presetsBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (equalizer == null) return;
                        short presetCount = equalizer.getNumberOfPresets();
                        if (presetCount == 0) {
                            toast("Aucun préréglage disponible");
                            return;
                        }
                        String[] names = new String[presetCount];
                        for (short i = 0; i < presetCount; i++) {
                            names[i] = equalizer.getPresetName(i);
                        }
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Préréglages")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (equalizer == null) return;
                                    equalizer.usePreset((short) which);
                                    syncEqualizerUi(bars, labels, minLevel);
                                    saveEqualizerLevels();
                                }
                            })
                            .show();
                    }
                });

            resetBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (equalizer == null) return;
                        for (short i = 0; i < bandCount; i++) {
                            equalizer.setBandLevel(i, (short) 0);
                        }
                        if (bassBoost != null) bassBoost.setStrength((short) 0);
                        if (virtualizer != null) virtualizer.setStrength((short) 0);
                        barBass.setProgress(0);
                        barVirt.setProgress(0);
                        syncEqualizerUi(bars, labels, minLevel);
                        saveEqualizerLevels();
                    }
                });

            // Une ligne par bande de l'égaliseur
            for (short i = 0; i < bandCount; i++) {
                final short band = i;

                TextView label = new TextView(this);
                label.setTextColor(0xFFFFFFFF);
                label.setPadding(0, dp(10), 0, dp(2));
                layout.addView(label);
                labels[i] = label;

                SeekBar bar = new SeekBar(this);
                bar.setMax(maxLevel - minLevel);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if (fromUser && equalizer != null) {
                                short level = (short) (progress + minLevel);
                                equalizer.setBandLevel(band, level);
                                labels[band].setText(eqLabel(band, level));
                            }
                        }
                        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                        @Override public void onStopTrackingTouch(SeekBar seekBar) {
                            saveEqualizerLevels();
                        }
                    });
                layout.addView(bar);
                bars[i] = bar;
            }

            syncEqualizerUi(bars, labels, minLevel);

            ScrollView scroll = new ScrollView(this);
            scroll.addView(layout);

            new AlertDialog.Builder(this)
                .setTitle("DSP & Égaliseur Audio")
                .setView(scroll)
                .setPositiveButton("Fermer", null)
                .show();

        } catch (Exception e) {
            toast("Erreur lors de la lecture des bandes DSP");
        }
    }
    
    

    // --- Méthodes statiques pour piloter la lecture depuis l'éditeur de paroles ---

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

    
}
