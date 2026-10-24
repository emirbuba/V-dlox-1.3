package com.oyunkayit;

/**
 * Vidlox yazılım AEC: oyun sesini (sistem yakalama) referans alıp mikrofona sızan
 * yankıyı çıkarır. Bölümlenmiş frekans-alanı uyarlamalı filtre (PBFDAF / NLMS).
 * Android'e bağımlı değildir. Mikrofonu kısmaz, sadece tahmin edilen yankıyı çıkarır.
 */
final class EchoCanceller {

    static final int N = 1024;            // blok boyu (örnek)
    private static final int F = 2 * N;   // FFT boyu
    private static final int P = 12;      // bölüm sayısı -> 12*1024 örnek ~ 279 ms yankı yolu
    private static final double MU_FAST = 1.0;    // ilk saniyelerde hızlı öğrenme
    private static final double MU_SLOW = 0.3;    // sonra konuşmayı korumak için yavaş
    private static final int WARMUP_BLOCKS = 200; // ~4.6 sn (oyun sesi varken sayılır)
    private static final double DELTA = 1.0e7;
    private static final double FAR_THR = 2500.0;   // referans enerji eşiği (rms ~50)

    private final double[] cosT = new double[F / 2];
    private final double[] sinT = new double[F / 2];
    private final int[] rev = new int[F];

    private final double[][] xr = new double[P][F];
    private final double[][] xi = new double[P][F];
    private final double[][] wr = new double[P][F];
    private final double[][] wi = new double[P][F];
    private final double[] prevRef = new double[N];
    private final double[] re = new double[F];
    private final double[] im = new double[F];
    private final double[] sr = new double[F];
    private final double[] si = new double[F];
    private final double[] er = new double[F];
    private final double[] ei = new double[F];
    private final double[] pw = new double[F];
    private final double[] e = new double[N];
    private int head = 0;
    private int constrainIdx = 0;
    private int badCount = 0;
    private int adaptBlocks = 0;

    EchoCanceller() {
        for (int i = 0; i < F / 2; i++) {
            double a = -2.0 * Math.PI * i / F;
            cosT[i] = Math.cos(a);
            sinT[i] = Math.sin(a);
        }
        int bits = Integer.numberOfTrailingZeros(F);
        for (int i = 0; i < F; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    void reset() {
        for (int p = 0; p < P; p++) {
            java.util.Arrays.fill(wr[p], 0);
            java.util.Arrays.fill(wi[p], 0);
        }
        badCount = 0;
        adaptBlocks = 0;
    }

    /** ref: oyun sesi (mono, N örnek), mic: mikrofon (N örnek), out: temizlenmiş mikrofon (N örnek). */
    void process(double[] ref, double[] mic, double[] out) {
        // 1) referans spektrumu (üst üste bindirmeli)
        System.arraycopy(prevRef, 0, re, 0, N);
        System.arraycopy(ref, 0, re, N, N);
        java.util.Arrays.fill(im, 0);
        System.arraycopy(ref, 0, prevRef, 0, N);
        fft(re, im, false);
        head = (head + 1) % P;
        System.arraycopy(re, 0, xr[head], 0, F);
        System.arraycopy(im, 0, xi[head], 0, F);

        double ex = 0;
        for (int n = 0; n < N; n++) ex += ref[n] * ref[n];
        ex /= N;

        // 2) yankı tahmini
        java.util.Arrays.fill(sr, 0);
        java.util.Arrays.fill(si, 0);
        for (int p = 0; p < P; p++) {
            int idx = (head - p + P) % P;
            double[] ar = wr[p], ai = wi[p], br = xr[idx], bi = xi[idx];
            for (int k = 0; k < F; k++) {
                sr[k] += ar[k] * br[k] - ai[k] * bi[k];
                si[k] += ar[k] * bi[k] + ai[k] * br[k];
            }
        }
        fft(sr, si, true);

        // 3) hata = mikrofon - tahmin edilen yankı
        double ed = 0, ee = 0;
        for (int n = 0; n < N; n++) {
            double y = sr[N + n];
            double v = mic[n] - y;
            e[n] = v;
            ed += mic[n] * mic[n];
            ee += v * v;
        }

        // 4) güvenlik: filtre bozulursa sıfırla ve mikrofonu aynen geçir
        boolean bad = Double.isNaN(ee) || Double.isInfinite(ee) || ee > 2.0 * ed + 1.0e6 * N / 1000.0;
        if (bad) {
            badCount++;
            if (badCount >= 3 || Double.isNaN(ee) || Double.isInfinite(ee)) {
                reset();
            }
            System.arraycopy(mic, 0, out, 0, N);
            return;
        }
        badCount = 0;
        System.arraycopy(e, 0, out, 0, N);

        // 5) uyarlama (sadece oyun sesi varken)
        if (ex > FAR_THR) {
            double mu = adaptBlocks < WARMUP_BLOCKS ? MU_FAST : MU_SLOW;
            adaptBlocks++;
            java.util.Arrays.fill(re, 0, N, 0);
            java.util.Arrays.fill(im, 0);
            for (int n = 0; n < N; n++) re[N + n] = e[n];
            fft(re, im, false);
            System.arraycopy(re, 0, er, 0, F);
            System.arraycopy(im, 0, ei, 0, F);

            for (int k = 0; k < F; k++) {
                double s = 0;
                for (int p = 0; p < P; p++) s += xr[p][k] * xr[p][k] + xi[p][k] * xi[p][k];
                pw[k] = mu / (s + DELTA);
            }
            for (int p = 0; p < P; p++) {
                int idx = (head - p + P) % P;
                double[] br = xr[idx], bi = xi[idx], ar = wr[p], ai = wi[p];
                for (int k = 0; k < F; k++) {
                    double gr = br[k] * er[k] + bi[k] * ei[k];
                    double gi = br[k] * ei[k] - bi[k] * er[k];
                    ar[k] += pw[k] * gr;
                    ai[k] += pw[k] * gi;
                }
            }
            // her blokta bir bölüme nedensellik kısıtı uygula
            int p = constrainIdx;
            constrainIdx = (constrainIdx + 1) % P;
            System.arraycopy(wr[p], 0, re, 0, F);
            System.arraycopy(wi[p], 0, im, 0, F);
            fft(re, im, true);
            java.util.Arrays.fill(re, N, F, 0);
            java.util.Arrays.fill(im, 0);
            fft(re, im, false);
            System.arraycopy(re, 0, wr[p], 0, F);
            System.arraycopy(im, 0, wi[p], 0, F);
        }
    }

    /** Yerinde radix-2 FFT. inverse=true ise 1/F ile ölçeklenir. */
    private void fft(double[] a, double[] b, boolean inverse) {
        for (int i = 0; i < F; i++) {
            int j = rev[i];
            if (j > i) {
                double t = a[i]; a[i] = a[j]; a[j] = t;
                t = b[i]; b[i] = b[j]; b[j] = t;
            }
        }
        for (int len = 2; len <= F; len <<= 1) {
            int half = len >> 1;
            int step = F / len;
            for (int i = 0; i < F; i += len) {
                for (int j = 0; j < half; j++) {
                    double wre = cosT[j * step];
                    double wim = inverse ? -sinT[j * step] : sinT[j * step];
                    int u = i + j, v = u + half;
                    double tr = a[v] * wre - b[v] * wim;
                    double ti = a[v] * wim + b[v] * wre;
                    a[v] = a[u] - tr; b[v] = b[u] - ti;
                    a[u] += tr;       b[u] += ti;
                }
            }
        }
        if (inverse) {
            double s = 1.0 / F;
            for (int i = 0; i < F; i++) { a[i] *= s; b[i] *= s; }
        }
    }
}
