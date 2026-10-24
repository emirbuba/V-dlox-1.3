package com.oyunkayit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecorderService extends Service {

    static final String ACTION_START = "com.oyunkayit.START";
    static final String ACTION_STOP = "com.oyunkayit.STOP";
    static volatile boolean running = false;

    private static final String TAG = "OyunKayit";
    private static final int RATE = 44100;
    private static final float SYS_GAIN = 0.8f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec venc;
    private MediaCodec aenc;
    private Surface inSurface;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private AudioRecord sysRec;
    private AudioRecord micRec;
    private EchoCanceller aecEngine;
    private boolean echoCancel = false;
    private float micGain = 1.5f;
    private int micSource = 0;
    private boolean baseSet = false;
    private Thread vThread;
    private Thread aThread;

    private boolean muxerStarted = false;
    private boolean wroteData = false;
    private boolean hasAudio = false;
    private int vTrack = -1;
    private int aTrack = -1;
    private long lastV = -1;
    private long lastA = -1;
    private long baseUs;

    private volatile boolean recording = false;
    private volatile long stopDeadline = Long.MAX_VALUE;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            if (running) {
                stopRecording();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (running || stopping.get()) {
            return START_NOT_STICKY;
        }

        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        int audioMode = sp.getInt("audio", 0);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioMode = 3;
        }

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (audioMode != 3) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        startForeground(1, buildNotification(), type);

        try {
            int code = intent.getIntExtra("code", 0);
            @SuppressWarnings("deprecation")
            Intent data = intent.getParcelableExtra("data");
            startRecording(code, data, sp, audioMode);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            toast("Kayıt başlatılamadı: " + e.getClass().getSimpleName());
            stopping.set(true);
            new Thread(this::doStop).start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rec", "Kayıt", NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification.Action act = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Durdur", stopPi).build();

        return new Notification.Builder(this, "rec")
                .setContentTitle("Vidlox kayıt yapıyor")
                .setContentText("Durdurmak için \"Durdur\"a bas")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPi)
                .addAction(act)
                .setOngoing(true)
                .build();
    }

    private void startRecording(int code, Intent data, SharedPreferences sp, int audioMode) throws Exception {
        int resP = sp.getInt("res", 720);
        int fps = sp.getInt("fps", 30);
        int orient = sp.getInt("orient", 0);
        echoCancel = sp.getInt("aec3", 1) == 1;
        micSource = sp.getInt("micsrc", 0);
        micGain = Math.max(0, Math.min(300, sp.getInt("micgain", 150))) / 100f;
        boolean useSys = audioMode == 0 || audioMode == 1;
        boolean useMic = audioMode == 0 || audioMode == 2;
        hasAudio = audioMode != 3;

        // Boyut hesabı
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int lng = Math.max(dm.widthPixels, dm.heightPixels);
        int sht = Math.min(dm.widthPixels, dm.heightPixels);
        float boxL = resP == 1080 ? 1920f : 1280f;
        float boxS = resP == 1080 ? 1080f : 720f;
        float scale = Math.min(1f, Math.min(boxL / lng, boxS / sht));
        int oL = Math.max(16, ((int) (lng * scale)) / 16 * 16);
        int oS = Math.max(16, ((int) (sht * scale)) / 16 * 16);
        int w = orient == 0 ? oL : oS;
        int h = orient == 0 ? oS : oL;
        int dpi = dm.densityDpi;

        baseUs = System.nanoTime() / 1000;

        // Çıkış dosyası (Galeri > Movies/OyunKayit)
        ContentValues cv = new ContentValues();
        String name = "Vidlox_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Vidlox");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (outUri == null) throw new IllegalStateException("dosya olusturulamadi");
        pfd = getContentResolver().openFileDescriptor(outUri, "rw");
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Video encoder
        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        long br = (long) (w * (long) h * fps * 0.17);
        br = Math.max(3000000L, Math.min(16000000L, br));
        vf.setInteger(MediaFormat.KEY_BIT_RATE, (int) br);
        vf.setInteger(MediaFormat.KEY_PRIORITY, 0);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inSurface = venc.createInputSurface();
        venc.start();

        // Ekran yakalama
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, main);
        display = projection.createVirtualDisplay("Vidlox", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inSurface, null, null);

        // Ses
        if (hasAudio) {
            try {
                setupAudio(useSys, useMic);
            } catch (Exception e) {
                Log.w(TAG, "audio setup failed", e);
                hasAudio = false;
                releaseAudio();
            }
        }

        recording = true;
        vThread = new Thread(this::videoLoop, "vid");
        vThread.start();
        if (hasAudio) {
            aThread = new Thread(this::audioLoop, "aud");
            aThread.start();
        }
        running = true;
    }

    private void setupAudio(boolean useSys, boolean useMic) throws Exception {
        if (useSys) {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            sysRec = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 8192) * 2)
                    .build();
            if (sysRec.getState() != AudioRecord.STATE_INITIALIZED) {
                sysRec.release();
                sysRec = null;
            }
        }
        if (useMic) {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int src = MediaRecorder.AudioSource.MIC;
            micRec = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096) * 2);
            if (micRec.getState() != AudioRecord.STATE_INITIALIZED) {
                micRec.release();
                micRec = null;
            }
        }
        if (micRec != null && micSource == 1) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        micRec.setPreferredDevice(d);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "mic select", e);
            }
        }
        if (sysRec == null && micRec == null) {
            hasAudio = false;
            return;
        }

        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aenc.start();
    }

    private void releaseAudio() {
        try { if (sysRec != null) { sysRec.release(); } } catch (Exception ignored) { }
        try { if (micRec != null) { micRec.release(); } } catch (Exception ignored) { }
        try { if (aenc != null) { aenc.release(); } } catch (Exception ignored) { }
        sysRec = null;
        micRec = null;
        aenc = null;
    }

    // ---------- Video ----------
    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                if (!recording && System.currentTimeMillis() > stopDeadline) break;
                int oi = venc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) {
                        vTrack = muxer.addTrack(venc.getOutputFormat());
                        tryStartLocked();
                    }
                } else if (oi >= 0) {
                    ByteBuffer b = venc.getOutputBuffer(oi);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && b != null) {
                        waitMuxer();
                        writeSample(true, b, info);
                    }
                    venc.releaseOutputBuffer(oi, false);
                    if (eos) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "video loop", e);
        }
    }

    private void waitMuxer() {
        long t0 = System.currentTimeMillis();
        synchronized (lock) {
            while (!muxerStarted) {
                long now = System.currentTimeMillis();
                if (!recording && now > stopDeadline) return;
                if (now - t0 > 3000 && vTrack >= 0) {
                    hasAudio = false;
                    tryStartLocked();
                    break;
                }
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void tryStartLocked() {
        if (muxerStarted) return;
        if (vTrack >= 0 && (!hasAudio || aTrack >= 0)) {
            muxer.start();
            muxerStarted = true;
            lock.notifyAll();
        }
    }

    private void writeSample(boolean video, ByteBuffer b, MediaCodec.BufferInfo info) {
        synchronized (lock) {
            if (!muxerStarted) return;
            int track = video ? vTrack : aTrack;
            if (track < 0) return;
            if (!baseSet) {
                if (!video) return;
                baseUs = info.presentationTimeUs;
                baseSet = true;
            }
            long adj = info.presentationTimeUs - baseUs;
            if (adj < 0) {
                if (!video) return;
                adj = 0;
            }
            long last = video ? lastV : lastA;
            if (adj <= last) adj = last + 1;
            if (video) lastV = adj; else lastA = adj;
            info.presentationTimeUs = adj;
            b.position(info.offset);
            b.limit(info.offset + info.size);
            try {
                muxer.writeSampleData(track, b, info);
                wroteData = true;
            } catch (Exception e) {
                Log.w(TAG, "write sample", e);
            }
        }
    }

    // ---------- Ses ----------
    private boolean readFully(AudioRecord rec, short[] buf, int count) {
        int off = 0;
        while (off < count && recording) {
            int n = rec.read(buf, off, count - off);
            if (n < 0) return false;
            off += n;
        }
        return off == count;
    }

    private void audioLoop() {
        final int frames = EchoCanceller.N;
        short[] sysBuf = new short[frames * 2];
        short[] micBuf = new short[frames];
        byte[] pcm = new byte[frames * 4];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long total = 0;
        // Yazılım AEC: hem oyun sesi (referans) hem mikrofon varken çalışır
        double[] aRef = null, aMic = null, aOut = null;
        if (echoCancel && sysRec != null && micRec != null) {
            try {
                aecEngine = new EchoCanceller();
                aRef = new double[frames];
                aMic = new double[frames];
                aOut = new double[frames];
            } catch (Throwable t) {
                aecEngine = null;
            }
        }
        try {
            if (sysRec != null) sysRec.startRecording();
            if (micRec != null) micRec.startRecording();
            long startUs = System.nanoTime() / 1000;

            while (recording) {
                if (sysRec != null && !readFully(sysRec, sysBuf, frames * 2)) break;
                if (micRec != null && !readFully(micRec, micBuf, frames)) break;
                if (!recording) break;

                if (aecEngine != null) {
                    try {
                        for (int i = 0; i < frames; i++) {
                            aRef[i] = (sysBuf[2 * i] + sysBuf[2 * i + 1]) * 0.5;
                            aMic[i] = micBuf[i];
                        }
                        aecEngine.process(aRef, aMic, aOut);
                    } catch (Throwable t) {
                        Log.w(TAG, "aec off", t);
                        aecEngine = null;
                    }
                }

                for (int i = 0; i < frames; i++) {
                    int l = 0;
                    int r = 0;
                    if (sysRec != null) {
                        l += (int) (sysBuf[2 * i] * SYS_GAIN);
                        r += (int) (sysBuf[2 * i + 1] * SYS_GAIN);
                    }
                    if (micRec != null) {
                        double micV = aecEngine != null ? aOut[i] : micBuf[i];
                        int m = (int) (micV * micGain);
                        l += m;
                        r += m;
                    }
                    l = soft(l);
                    r = soft(r);
                    if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
                    if (r > 32767) r = 32767; else if (r < -32768) r = -32768;
                    pcm[4 * i] = (byte) l;
                    pcm[4 * i + 1] = (byte) (l >> 8);
                    pcm[4 * i + 2] = (byte) r;
                    pcm[4 * i + 3] = (byte) (r >> 8);
                }

                long pts = startUs + total * 1000000L / RATE;
                int ii = aenc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer ib = aenc.getInputBuffer(ii);
                    ib.clear();
                    ib.put(pcm, 0, frames * 4);
                    aenc.queueInputBuffer(ii, 0, frames * 4, pts, 0);
                }
                total += frames;
                drainAudio(info, 0);
            }

            // Bitiş sinyali
            long endPts = startUs + total * 1000000L / RATE;
            int ii = aenc.dequeueInputBuffer(100000);
            if (ii >= 0) {
                aenc.queueInputBuffer(ii, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            long limit = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < limit) {
                if (drainAudio(info, 10000)) break;
            }
        } catch (Exception e) {
            Log.e(TAG, "audio loop", e);
        }
    }

    // Yüksek seslerde sert kırpma (cızırtı) yerine yumuşak sınırlama
    private static int soft(int v) {
        int a = Math.abs(v);
        if (a <= 24000) return v;
        int o = (int) (24000 + 8767 * Math.tanh((a - 24000) / 8767.0));
        return v < 0 ? -o : o;
    }

    private boolean drainAudio(MediaCodec.BufferInfo info, long timeoutUs) {
        while (true) {
            int oi = aenc.dequeueOutputBuffer(info, timeoutUs);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) {
                    if (!muxerStarted) {
                        aTrack = muxer.addTrack(aenc.getOutputFormat());
                        tryStartLocked();
                    }
                }
            } else if (oi >= 0) {
                ByteBuffer b = aenc.getOutputBuffer(oi);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && b != null) writeSample(false, b, info);
                aenc.releaseOutputBuffer(oi, false);
                if (eos) return true;
            }
        }
    }

    // ---------- Durdurma ----------
    private void stopRecording() {
        if (!stopping.compareAndSet(false, true)) return;
        new Thread(this::doStop, "stop").start();
    }

    private void joinQuiet(Thread t) {
        if (t == null) return;
        try {
            t.join(5000);
        } catch (InterruptedException ignored) {
        }
    }

    private void doStop() {
        stopDeadline = System.currentTimeMillis() + 3000;
        recording = false;
        try { if (venc != null && vThread != null) venc.signalEndOfInputStream(); } catch (Exception e) { Log.w(TAG, "eos", e); }
        joinQuiet(aThread);
        joinQuiet(vThread);

        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (sysRec != null) sysRec.stop(); } catch (Exception ignored) { }
        try { if (micRec != null) micRec.stop(); } catch (Exception ignored) { }
        try { if (venc != null) venc.stop(); } catch (Exception ignored) { }
        try { if (aenc != null) aenc.stop(); } catch (Exception ignored) { }
        try { if (venc != null) venc.release(); } catch (Exception ignored) { }
        try { if (sysRec != null) sysRec.release(); } catch (Exception ignored) { }
        try { if (micRec != null) micRec.release(); } catch (Exception ignored) { }
        try { if (aenc != null) aenc.release(); } catch (Exception ignored) { }
        try { if (inSurface != null) inSurface.release(); } catch (Exception ignored) { }

        boolean ok = false;
        synchronized (lock) {
            try {
                if (muxer != null && muxerStarted) {
                    muxer.stop();
                    ok = wroteData;
                }
            } catch (Exception e) {
                Log.w(TAG, "muxer stop", e);
                ok = false;
            }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }

        try {
            if (outUri != null) {
                if (ok) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, cv, null, null);
                } else {
                    getContentResolver().delete(outUri, null, null);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "finalize", e);
        }

        running = false;
        if (ok) toast("Kayıt kaydedildi: Galeri > Movies/Vidlox");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void toast(final String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }
}
