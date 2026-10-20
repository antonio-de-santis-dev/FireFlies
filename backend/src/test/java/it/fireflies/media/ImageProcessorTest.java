package it.fireflies.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.fireflies.common.ApiException;
import it.fireflies.support.TestImages;

/** Verifica e ricodifica delle foto, senza Spring né database. */
class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void jpegGrandeProduceTreVariantiJpegProgressiveSenzaIngrandire() {
        ImageProcessor.Processed p = processor.process(TestImages.jpeg(2400, 1600));
        assertThat(p.sourceMimeType()).isEqualTo("image/jpeg");
        assertThat(p.variants()).extracting(ImageProcessor.Variant::size)
                .containsExactly(ImageProcessor.Size.W480, ImageProcessor.Size.W960, ImageProcessor.Size.W1600);
        assertThat(p.variants()).extracting(ImageProcessor.Variant::width).containsExactly(480, 960, 1600);
        assertThat(p.variants()).extracting(ImageProcessor.Variant::height).containsExactly(320, 640, 1067);
        assertThat(p.variants()).allSatisfy(v -> {
            assertThat(v.mimeType()).isEqualTo("image/jpeg");
            assertThat(isProgressiveJpeg(v.bytes())).as("JPEG progressivo").isTrue();
        });
    }

    /**
     * Locandine (FASE 7 · requisito 15): le varianti si riducono solo in scala, con le proporzioni dell'originale e
     * l'immagine intera — nessun ritaglio per locandine verticali, quadrate o orizzontali.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1080, 1920", "1240, 1754", "1200, 1200", "1920, 1080"})
    void locandineMaiRitagliateNelleVarianti(int width, int height) throws Exception {
        ImageProcessor.Processed p = processor.process(TestImages.jpeg(width, height));
        assertThat(p.width()).isEqualTo(width);
        assertThat(p.height()).isEqualTo(height);
        for (ImageProcessor.Variant v : p.variants()) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(v.bytes()));
            assertThat(img.getWidth()).isEqualTo(v.width());
            assertThat(img.getHeight()).isEqualTo(v.height());
            // altezza proporzionale alla larghezza (arrotondamento di un pixel al massimo)
            assertThat(Math.abs(v.height() - (double) height * v.width() / width)).as(v.size().name()).isLessThanOrEqualTo(1.0);
        }
    }

    @Test
    void fotoStrettaNonVieneMaiIngrandita() {
        ImageProcessor.Processed p = processor.process(TestImages.jpeg(700, 500));
        assertThat(p.variants()).extracting(ImageProcessor.Variant::width).containsExactly(480, 700);
        assertThat(p.variants().getLast().size()).isEqualTo(ImageProcessor.Size.W960);
    }

    @Test
    void exifEGpsRimossiEOrientamentoApplicato() {
        byte[] original = TestImages.jpegWithExif(800, 400, 6); // 90° in senso orario
        assertThat(TestImages.contains(original, TestImages.GPS_MARKER)).as("il file di partenza contiene il GPS").isTrue();

        ImageProcessor.Processed p = processor.process(original);
        assertThat(p.width()).isEqualTo(400);
        assertThat(p.height()).isEqualTo(800);
        for (ImageProcessor.Variant v : p.variants()) {
            assertThat(TestImages.contains(v.bytes(), "Exif")).as("nessun EXIF in " + v.size()).isFalse();
            assertThat(TestImages.contains(v.bytes(), "GPS")).as("nessun GPS in " + v.size()).isFalse();
        }
        // la metà sinistra (rossa) dopo la rotazione oraria è in alto
        BufferedImage w480 = TestImages.read(p.variants().getFirst().bytes());
        assertThat(w480.getWidth()).isLessThan(w480.getHeight());
        assertThat(red(w480.getRGB(w480.getWidth() / 2, 10))).isGreaterThan(200);
        assertThat(blue(w480.getRGB(w480.getWidth() / 2, w480.getHeight() - 10))).isGreaterThan(200);
    }

    @Test
    void pngConTrasparenzaRestaPngAPaletteConCanaleAlfa() {
        ImageProcessor.Processed p = processor.process(TestImages.png(600, 900, true));
        assertThat(p.variants()).allSatisfy(v -> assertThat(v.mimeType()).isEqualTo("image/png"));
        byte[] w480 = p.variants().getFirst().bytes();
        assertThat(TestImages.contains(w480, "PLTE")).isTrue();
        assertThat(TestImages.contains(w480, "tRNS")).isTrue();
        BufferedImage decoded = TestImages.read(w480);
        assertThat(decoded.getRGB(10, 10) >>> 24).as("zona trasparente").isZero();
        assertThat(decoded.getRGB(decoded.getWidth() - 10, 10) >>> 24).as("zona piena").isEqualTo(255);
    }

    @Test
    void pngOpacoDiventaJpeg() {
        ImageProcessor.Processed p = processor.process(TestImages.png(600, 400, false));
        assertThat(p.sourceMimeType()).isEqualTo("image/png");
        assertThat(p.variants()).allSatisfy(v -> assertThat(v.mimeType()).isEqualTo("image/jpeg"));
    }

    @Test
    void webpRealeDelLocaleAccettato() {
        ImageProcessor.Processed photo = processor.process(TestImages.resource("foto-locale.webp"));
        assertThat(photo.sourceMimeType()).isEqualTo("image/webp");
        assertThat(photo.variants()).allSatisfy(v -> assertThat(v.mimeType()).isEqualTo("image/jpeg"));

        ImageProcessor.Processed cutout = processor.process(TestImages.resource("scontornata.webp"));
        assertThat(cutout.variants()).allSatisfy(v -> assertThat(v.mimeType()).isEqualTo("image/png"));
        int bytes = cutout.variants().stream().mapToInt(v -> v.bytes().length).max().orElseThrow();
        assertThat(bytes).as("PNG8 compresso: ben sotto i 400 KB di un PNG a 32 bit").isLessThan(250_000);
    }

    @Test
    void fileCheNonSonoFotoRifiutatiDaiPrimiByte() {
        List<byte[]> fakes = List.of(
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),
                "GIF89a................".getBytes(StandardCharsets.US_ASCII),
                "%PDF-1.7 documento".getBytes(StandardCharsets.US_ASCII),
                "MZ eseguibile rinominato in .jpg".getBytes(StandardCharsets.US_ASCII));
        for (byte[] fake : fakes) {
            assertThatThrownBy(() -> processor.process(fake)).isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "MEDIA_INVALID")
                    .hasMessageContaining("JPG, PNG o WebP");
        }
    }

    @Test
    void heicHaUnMessaggioDedicato() {
        byte[] heic = new byte[64];
        System.arraycopy("\0\0\0\u0018ftypheic".getBytes(StandardCharsets.ISO_8859_1), 0, heic, 0, 12);
        assertThatThrownBy(() -> processor.process(heic)).isInstanceOf(ApiException.class).hasMessageContaining("HEIC");
    }

    @Test
    void jpegTroncatoODanneggiatoRifiutato() {
        byte[] jpeg = TestImages.jpeg(800, 600);
        byte[] truncated = java.util.Arrays.copyOf(jpeg, 400);
        assertThatThrownBy(() -> processor.process(truncated)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEDIA_INVALID");
    }

    @Test
    void decompressionBombRifiutataPrimaDiDecodificare() {
        byte[] bomb = TestImages.pngHeader(60_000, 60_000);
        assertThat(bomb.length).isLessThan(100);
        assertThatThrownBy(() -> processor.process(bomb)).isInstanceOf(ApiException.class)
                .hasMessageContaining("troppo grande");
    }

    @Test
    void fotoTroppoPiccolaRifiutata() {
        assertThatThrownBy(() -> processor.process(TestImages.jpeg(150, 150))).isInstanceOf(ApiException.class)
                .hasMessageContaining("troppo piccola");
    }

    @Test
    void oltreCinqueMegabyteRifiutataSenzaLeggerla() {
        byte[] big = new byte[ImageProcessor.MAX_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> processor.process(big)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEDIA_TOO_LARGE");
    }

    @Test
    void fileVuotoRifiutato() {
        assertThatThrownBy(() -> processor.process(new byte[0])).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEDIA_INVALID");
    }

    private static boolean isProgressiveJpeg(byte[] d) {
        for (int i = 2; i + 1 < d.length; i++) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xC2) {
                return true;
            }
        }
        return false;
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
