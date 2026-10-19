package it.fireflies.media;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Riduzione a 256 colori con trasparenza (PNG8) per le immagini scontornate, come fa pngquant:
 * taglio mediano sullo spazio RGBA e diffusione dell'errore di Floyd–Steinberg sul colore.
 * Un PNG a 32 bit di un titolare scontornato pesa circa 430 KB; in PNG8 circa 70 KB, senza differenze visibili.
 */
final class PaletteQuantizer {

    private static final int COLORS = 255; // + l'indice 0, completamente trasparente
    private static final int TRANSPARENT_BELOW = 8;
    private static final int MAX_SAMPLES = 250_000;
    /** Diffusione dell'errore attenuata: meno «rumore» sulle tinte unite e file più compressi. */
    private static final float DITHER = 0.8f;

    private PaletteQuantizer() {
    }

    static BufferedImage quantize(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] argb = source.getRGB(0, 0, w, h, null, 0, w);
        int[] palette = palette(argb);

        byte[] r = new byte[palette.length + 1];
        byte[] g = new byte[palette.length + 1];
        byte[] b = new byte[palette.length + 1];
        byte[] a = new byte[palette.length + 1];
        for (int i = 0; i < palette.length; i++) {
            r[i + 1] = (byte) (palette[i] >>> 16);
            g[i + 1] = (byte) (palette[i] >>> 8);
            b[i + 1] = (byte) palette[i];
            a[i + 1] = (byte) (palette[i] >>> 24);
        }
        IndexColorModel model = new IndexColorModel(8, palette.length + 1, r, g, b, a);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, model);
        WritableRaster raster = out.getRaster();

        // errore di diffusione per riga corrente e successiva (r, g, b)
        float[][] err = new float[2][(w + 2) * 3];
        Map<Integer, Integer> nearest = new HashMap<>();
        byte[] row = new byte[w];
        for (int y = 0; y < h; y++) {
            float[] cur = err[y & 1];
            float[] next = err[(y + 1) & 1];
            Arrays.fill(next, 0f);
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int alpha = p >>> 24;
                if (alpha < TRANSPARENT_BELOW) {
                    row[x] = 0;
                    continue;
                }
                int e = (x + 1) * 3;
                int pr = clamp(((p >> 16) & 0xFF) + cur[e]);
                int pg = clamp(((p >> 8) & 0xFF) + cur[e + 1]);
                int pb = clamp((p & 0xFF) + cur[e + 2]);
                int key = (alpha >> 3) << 15 | (pr >> 3) << 10 | (pg >> 3) << 5 | (pb >> 3);
                int index = nearest.computeIfAbsent(key, k -> closest(palette, alpha, pr, pg, pb));
                row[x] = (byte) (index + 1);
                int c = palette[index];
                float er = (pr - ((c >> 16) & 0xFF)) * DITHER;
                float eg = (pg - ((c >> 8) & 0xFF)) * DITHER;
                float eb = (pb - (c & 0xFF)) * DITHER;
                spread(cur, e + 3, er, eg, eb, 7 / 16f);
                spread(next, e - 3, er, eg, eb, 3 / 16f);
                spread(next, e, er, eg, eb, 5 / 16f);
                spread(next, e + 3, er, eg, eb, 1 / 16f);
            }
            raster.setDataElements(0, y, w, 1, row);
        }
        return out;
    }

    /**
     * PNG a palette scritto direttamente: filtro «None» su ogni riga e deflate al livello 9. Per le immagini a
     * palette è la combinazione più compatta (il writer del JDK applica filtri adattivi pensati per RGB).
     */
    static byte[] encodePng(BufferedImage indexed) {
        IndexColorModel model = (IndexColorModel) indexed.getColorModel();
        int w = indexed.getWidth();
        int h = indexed.getHeight();
        int size = model.getMapSize();
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream hd = new DataOutputStream(header);
            hd.writeInt(w);
            hd.writeInt(h);
            hd.write(new byte[] {8, 3, 0, 0, 0}); // 8 bit, palette, deflate, filtri standard, non interlacciato
            chunk(png, "IHDR", header.toByteArray());

            byte[] plte = new byte[size * 3];
            byte[] trns = new byte[size];
            for (int i = 0; i < size; i++) {
                plte[i * 3] = (byte) model.getRed(i);
                plte[i * 3 + 1] = (byte) model.getGreen(i);
                plte[i * 3 + 2] = (byte) model.getBlue(i);
                trns[i] = (byte) model.getAlpha(i);
            }
            chunk(png, "PLTE", plte);
            chunk(png, "tRNS", trns);

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream z = new DeflaterOutputStream(data, deflater)) {
                byte[] row = new byte[w];
                for (int y = 0; y < h; y++) {
                    indexed.getRaster().getDataElements(0, y, w, 1, row);
                    z.write(0); // filtro None
                    z.write(row);
                }
            } finally {
                deflater.end();
            }
            chunk(png, "IDAT", data.toByteArray());
            chunk(png, "IEND", new byte[0]);
            return png.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // solo memoria: non succede
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        d.writeInt(data.length);
        d.write(name);
        d.write(data);
        d.writeInt((int) crc.getValue());
    }

    /** Taglio mediano: si divide sempre il gruppo più «esteso» lungo il canale con l'intervallo maggiore. */
    private static int[] palette(int[] argb) {
        int step = Math.max(1, argb.length / MAX_SAMPLES);
        int[] samples = new int[argb.length / step + 1];
        int n = 0;
        for (int i = 0; i < argb.length; i += step) {
            if ((argb[i] >>> 24) >= TRANSPARENT_BELOW) {
                samples[n++] = argb[i];
            }
        }
        if (n == 0) {
            return new int[] {0xFF000000};
        }
        List<int[]> boxes = new ArrayList<>(); // {inizio, fine (esclusa)}
        boxes.add(new int[] {0, n});
        while (boxes.size() < COLORS) {
            int best = -1;
            long bestScore = 0;
            int bestChannel = 0;
            for (int i = 0; i < boxes.size(); i++) {
                int[] box = boxes.get(i);
                if (box[1] - box[0] < 2) {
                    continue;
                }
                int[] range = ranges(samples, box[0], box[1]);
                int channel = 0;
                for (int c = 1; c < 4; c++) {
                    if (range[c] > range[channel]) {
                        channel = c;
                    }
                }
                long score = (long) range[channel] * (box[1] - box[0]);
                if (range[channel] > 0 && score > bestScore) {
                    best = i;
                    bestScore = score;
                    bestChannel = channel;
                }
            }
            if (best < 0) {
                break;
            }
            int[] box = boxes.remove(best);
            sortByChannel(samples, box[0], box[1], bestChannel);
            int mid = (box[0] + box[1]) >>> 1;
            boxes.add(new int[] {box[0], mid});
            boxes.add(new int[] {mid, box[1]});
        }
        int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            int[] box = boxes.get(i);
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int j = box[0]; j < box[1]; j++) {
                int p = samples[j];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            int count = box[1] - box[0];
            palette[i] = (int) (sa / count) << 24 | (int) (sr / count) << 16 | (int) (sg / count) << 8 | (int) (sb / count);
        }
        return palette;
    }

    private static int[] ranges(int[] samples, int from, int to) {
        int[] min = {255, 255, 255, 255};
        int[] max = {0, 0, 0, 0};
        for (int i = from; i < to; i++) {
            for (int c = 0; c < 4; c++) {
                int v = channel(samples[i], c);
                min[c] = Math.min(min[c], v);
                max[c] = Math.max(max[c], v);
            }
        }
        return new int[] {max[0] - min[0], max[1] - min[1], max[2] - min[2], max[3] - min[3]};
    }

    private static void sortByChannel(int[] samples, int from, int to, int c) {
        long[] keyed = new long[to - from];
        for (int i = from; i < to; i++) {
            keyed[i - from] = ((long) channel(samples[i], c) << 32) | (samples[i] & 0xFFFFFFFFL);
        }
        Arrays.sort(keyed);
        for (int i = from; i < to; i++) {
            samples[i] = (int) keyed[i - from];
        }
    }

    /** Canali: 0 = alfa, 1 = rosso, 2 = verde, 3 = blu. */
    private static int channel(int p, int c) {
        return (p >>> (24 - 8 * c)) & 0xFF;
    }

    private static int closest(int[] palette, int a, int r, int g, int b) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];
            long da = a - (c >>> 24);
            long dr = r - ((c >> 16) & 0xFF);
            long dg = g - ((c >> 8) & 0xFF);
            long db = b - (c & 0xFF);
            long d = 2 * da * da + 3 * dr * dr + 4 * dg * dg + 2 * db * db; // l'occhio pesa più il verde
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static void spread(float[] row, int i, float er, float eg, float eb, float factor) {
        if (i < 0 || i + 2 >= row.length) {
            return;
        }
        row[i] += er * factor;
        row[i + 1] += eg * factor;
        row[i + 2] += eb * factor;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }
}
