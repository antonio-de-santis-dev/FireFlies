package it.fireflies.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.http.HttpStatus;

import it.fireflies.common.ApiException;

/**
 * Verifica e ricodifica di una foto caricata (ADR-0004, P-17). Nulla di quanto arriva dal browser viene pubblicato
 * così com'è:
 * <ol>
 *   <li>il tipo si riconosce dai primi byte (non dal nome né dal content-type dichiarato): JPEG, PNG o WebP;</li>
 *   <li>le dimensioni si leggono dall'intestazione <em>prima</em> di decodificare (difesa dalle «decompression bomb»);</li>
 *   <li>l'orientamento EXIF dei telefoni viene applicato ai pixel;</li>
 *   <li>si scrivono varianti nuove (480, 960, 1600 px di larghezza, mai ingrandite): JPEG progressivo, oppure PNG a
 *       256 colori ({@link PaletteQuantizer}) se l'immagine ha zone trasparenti. Le varianti nascono dai soli pixel:
 *       EXIF, GPS e altri metadati spariscono.</li>
 * </ol>
 */
final class ImageProcessor {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIDE = 12_000;
    static final long MAX_PIXELS = 50_000_000L;
    static final int MIN_SIDE = 200;
    static final float JPEG_QUALITY = 0.82f;

    enum Size {
        W480(480), W960(960), W1600(1600);

        final int width;

        Size(int width) {
            this.width = width;
        }
    }

    record Variant(Size size, int width, int height, String mimeType, byte[] bytes) {
        String fileName() {
            return size.name().toLowerCase() + ("image/png".equals(mimeType) ? ".png" : ".jpg");
        }
    }

    record Processed(String sourceMimeType, int bytes, int width, int height, String sha256, List<Variant> variants) {
    }

    private enum Format {
        JPEG("image/jpeg", "jpeg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

        final String mimeType;
        final String readerName;

        Format(String mimeType, String readerName) {
            this.mimeType = mimeType;
            this.readerName = readerName;
        }
    }

    static {
        ImageIO.setUseCache(false); // niente file temporanei: tutto in memoria, entro i limiti sopra
        ImageIO.scanForPlugins();
    }

    Processed process(byte[] data) {
        if (data == null || data.length == 0) {
            throw invalid("Il file è vuoto.");
        }
        if (data.length > MAX_BYTES) {
            throw tooLarge();
        }
        Format format = detect(data);
        BufferedImage decoded = decode(data, format);
        BufferedImage image = format == Format.JPEG ? ExifOrientation.apply(decoded, ExifOrientation.read(data)) : decoded;
        boolean transparent = hasTransparency(image);
        String mime = transparent ? "image/png" : "image/jpeg";

        List<Variant> variants = new ArrayList<>();
        for (Size size : Size.values()) {
            int width = Math.min(size.width, image.getWidth());
            BufferedImage scaled = scale(image, width, transparent);
            variants.add(new Variant(size, scaled.getWidth(), scaled.getHeight(), mime, encode(scaled, transparent)));
            if (width == image.getWidth()) {
                break; // mai ingrandire: l'ultima variante è la foto alla sua larghezza
            }
        }
        return new Processed(format.mimeType, data.length, image.getWidth(), image.getHeight(), sha256(data), variants);
    }

    private static Format detect(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return Format.PNG;
        }
        if (d.length >= 12 && ascii(d, 0, 4).equals("RIFF") && ascii(d, 8, 4).equals("WEBP")) {
            return Format.WEBP;
        }
        if (d.length >= 12 && ascii(d, 4, 4).equals("ftyp") && ascii(d, 8, 3).matches("hei|hev|mif|msf|avi")) {
            throw invalid("Le foto HEIC/AVIF non sono supportate: sul telefono scegli il formato «Più compatibile» "
                    + "(JPG) oppure esportala in JPG, poi riprova.");
        }
        throw invalid("Il file non è una foto JPG, PNG o WebP.");
    }

    private static BufferedImage decode(byte[] data, Format format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.readerName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("Nessun lettore ImageIO per " + format);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                throw invalid("La foto è troppo grande (" + width + "×" + height
                        + " pixel): il massimo è 50 megapixel e 12.000 pixel per lato.");
            }
            if (Math.min(width, height) < MIN_SIDE) {
                throw invalid("La foto è troppo piccola (" + width + "×" + height
                        + " pixel): servono almeno " + MIN_SIDE + " pixel per lato.");
            }
            // Le foto molto grandi si leggono già ridotte (meno memoria); la qualità finale la dà la riduzione sotto.
            ImageReadParam param = reader.getDefaultReadParam();
            int step = readStep(width, height);
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                throw invalid("Il file non si apre come immagine.");
            }
            return image;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid("Il file non si apre come immagine: potrebbe essere danneggiato.");
        } finally {
            reader.dispose();
        }
    }

    /** Pixel massimi decodificati in memoria: 16 MP ≈ 64 MB di raster a 8 bit per canale. */
    static final long MAX_DECODED_PIXELS = 16_000_000L;

    /**
     * Passo di sottocampionamento in lettura. FIX 3 (B-09): prima dipendeva solo dalla larghezza, quindi una foto alta e
     * stretta dentro i limiti (es. 3.200×12.000, 38 MP) si decodificava intera, circa 150 MB (il doppio per un PNG a 16
     * bit) contro un heap di produzione di circa 540 MB. Ora il passo rispetta anche un tetto di pixel decodificati.
     */
    static int readStep(int width, int height) {
        int byWidth = Math.max(1, width / (Size.W1600.width * 2));
        int byPixels = (int) Math.ceil(Math.sqrt((double) width * height / MAX_DECODED_PIXELS));
        return Math.max(byWidth, Math.max(1, byPixels));
    }

    /** Trasparenza reale: un PNG «con alfa» ma tutto opaco diventa comunque JPEG (molto più leggero). */
    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        WritableRaster alpha = image.getAlphaRaster();
        if (alpha == null) {
            return false;
        }
        int[] row = new int[alpha.getWidth()];
        for (int y = 0; y < alpha.getHeight(); y++) {
            alpha.getSamples(0, y, alpha.getWidth(), 1, 0, row);
            for (int a : row) {
                if (a < 250) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Riduzione a passi dimezzati (bilineare): niente scalettature sulle foto grandi. */
    static BufferedImage scale(BufferedImage source, int targetWidth, boolean alpha) {
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        do {
            width = Math.max(targetWidth, width / 2);
            height = width == targetWidth ? targetHeight : Math.max(targetHeight, height / 2);
            BufferedImage next = new BufferedImage(width, height, type);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                if (!alpha) {
                    g.setColor(java.awt.Color.WHITE); // eventuali pixel semitrasparenti su fondo bianco
                    g.fillRect(0, 0, width, height);
                }
                g.drawImage(current, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            current = next;
        } while (width != targetWidth);
        return current;
    }

    private static byte[] encode(BufferedImage image, boolean png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (png) {
                return PaletteQuantizer.encodePng(PaletteQuantizer.quantize(image));
            }
            ImageWriter writer = jdkJpegWriter();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT); // caricamento progressivo nel browser
            try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param); // nessun metadato copiato
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Codifica della variante non riuscita", e);
        }
    }

    private static ImageWriter jdkJpegWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter fallback = null;
        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            if (writer.getClass().getName().startsWith("com.sun.imageio")) {
                return writer;
            }
            fallback = writer;
        }
        if (fallback == null) {
            throw new IllegalStateException("Nessun writer JPEG");
        }
        return fallback;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA_INVALID", "Foto non valida", detail);
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "MEDIA_TOO_LARGE", "Foto troppo pesante",
                "La foto pesa più di 5 MB: riducila (o esportala in JPG) e riprova.", Map.of("maxBytes", MAX_BYTES));
    }

    /** Orientamento EXIF (tag 0x0112) delle foto JPEG scattate col telefono. */
    static final class ExifOrientation {

        private ExifOrientation() {
        }

        /** 1 = normale; 2…8 = specchiature/rotazioni. Qualunque anomalia → 1 (si tiene la foto com'è). */
        static int read(byte[] d) {
            try {
                int i = 2;
                while (i + 4 <= d.length && (d[i] & 0xFF) == 0xFF) {
                    int marker = d[i + 1] & 0xFF;
                    if (marker == 0xDA || marker == 0xD9) {
                        return 1; // inizio dei dati compressi: nessun EXIF prima
                    }
                    int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                    if (marker == 0xE1 && length > 14 && i + 2 + length <= d.length
                            && ascii(d, i + 4, 4).equals("Exif") && d[i + 8] == 0 && d[i + 9] == 0) {
                        return fromTiff(d, i + 10, i + 2 + length);
                    }
                    i += 2 + length;
                }
            } catch (RuntimeException e) {
                return 1;
            }
            return 1;
        }

        private static int fromTiff(byte[] d, int tiff, int end) {
            boolean little = d[tiff] == 'I' && d[tiff + 1] == 'I';
            if (!little && !(d[tiff] == 'M' && d[tiff + 1] == 'M')) {
                return 1;
            }
            int ifd = tiff + int32(d, tiff + 4, little);
            int entries = int16(d, ifd, little);
            for (int e = 0; e < entries; e++) {
                int entry = ifd + 2 + e * 12;
                if (entry + 12 > end) {
                    return 1;
                }
                if (int16(d, entry, little) == 0x0112) {
                    int value = int16(d, entry + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        }

        private static int int16(byte[] d, int i, boolean little) {
            return little ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) : ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        }

        private static int int32(byte[] d, int i, boolean little) {
            return little
                    ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16) | ((d[i + 3] & 0xFF) << 24)
                    : ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
        }

        /** Applica l'orientamento ai pixel: (x, y) → (x', y') secondo la specifica EXIF. */
        static BufferedImage apply(BufferedImage src, int orientation) {
            if (orientation <= 1 || orientation > 8) {
                return src;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            AffineTransform t = switch (orientation) {
                case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // specchio orizzontale
                case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // 180°
                case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // specchio verticale
                case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // trasposta
                case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // 90° orario
                case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // trasversa
                default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: 90° antiorario
            };
            boolean swap = orientation >= 5;
            BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h,
                    src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.drawImage(src, t, null);
            } finally {
                g.dispose();
            }
            return dst;
        }
    }
}
