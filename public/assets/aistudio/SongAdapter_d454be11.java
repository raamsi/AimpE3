package com.ramsi.aimpe3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;

class SongAdapter extends BaseAdapter {

    private static final int ART_CACHE_BYTES = 6 * 1024 * 1024;
    private static final int ART_MAX_DIMENSION = 256;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService ART_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> ART_CACHE =
        new LruCache<String, Bitmap>(ART_CACHE_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight();
            }
        };

    private final Context context;
    private final ArrayList<Song> songList;
    private final LayoutInflater inflater;

    public SongAdapter(Context context, ArrayList<Song> songList) {
        this.context = context.getApplicationContext();
        this.songList = songList;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return songList.size();
    }

    @Override
    public Object getItem(int position) {
        return songList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_song, parent, false);
            holder = new ViewHolder();
            holder.imgCover = (ImageView) convertView.findViewById(R.id.imgSongCover);
            holder.tvTitle = (TextView) convertView.findViewById(R.id.tvSongTitle);
            holder.tvArtist = (TextView) convertView.findViewById(R.id.tvSongArtist);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Song song = songList.get(position);
        holder.tvTitle.setText(song.getTitle());
        holder.tvArtist.setText(song.getArtist());

        setThumbnail(song.getPath(), holder.imgCover);

        return convertView;
    }

    private void setThumbnail(final String path, ImageView imageView) {
        imageView.setTag(path);
        imageView.setImageResource(android.R.drawable.ic_media_play);
        if (path == null || path.isEmpty()) return;

        Bitmap cached = ART_CACHE.get(path);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        final WeakReference<ImageView> target = new WeakReference<ImageView>(imageView);
        ART_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loadThumbnail(path);
                if (bitmap == null) return;
                ART_CACHE.put(path, bitmap);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        ImageView view = target.get();
                        if (view != null && path.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
    }

    private Bitmap loadThumbnail(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        byte[] artBytes = null;
        try {
            if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path));
            } else {
                retriever.setDataSource(path);
            }
            artBytes = retriever.getEmbeddedPicture();
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        if (artBytes == null || artBytes.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, options);
        if (decoded == null) return null;

        Bitmap rounded = getRoundedCornerBitmap(decoded, dp(10f));
        decoded.recycle();
        return rounded;
    }

    private int calculateSampleSize(int width, int height) {
        int sampleSize = 1;
        while (Math.max(width / sampleSize, height / sampleSize) > ART_MAX_DIMENSION) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private Bitmap getRoundedCornerBitmap(Bitmap bitmap, float pixels) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        RectF rectF = new RectF(rect);

        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawRoundRect(rectF, pixels, pixels, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    static class ViewHolder {
        ImageView imgCover;
        TextView tvTitle;
        TextView tvArtist;
    }
}

