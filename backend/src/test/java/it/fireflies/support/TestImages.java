package it.fireflies.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/** Immagini generate al volo per i test (nessun file di terzi), più due WebP reali del locale in {@code src/test/resources/media}. */
public final class TestImages {

    public static final String GPS_MARKER = "GPS 40.2010N 18.3020E fotocamera-di-prova";

    private TestImages() {
    }

    /** JPEG con metà sinistra rossa e metà destra blu: serve a verificare le rotazioni. */
    public static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return write(image, "jpeg");
    }

    /** PNG RGBA; con {@code transparent} metà immagine è completamente trasparente (come un soggetto scontornato). */
    public static byte[] png(int width, int height, boolean transparent) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(240, 180, 20));
        g.fillRect(0, 0, width, height);
        if (transparent) {
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, width / 2, height);
        }
        g.dispose();
        return write(image, "png");
    }

    /**
     * JPEG con un segmento EXIF come quelli dei telefoni: orientamento e un testo con coordinate GPS
     * (nel tag ImageDescription), che non deve mai arrivare nelle varianti pubblicate.
     */
    public static byte[] jpegWithExif(int width, int height, int orientation) {
        byte[] jpeg = jpeg(width, height);
        byte[] description = (GPS_MARKER + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + description.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(8 + 2 + 24 + 4); // ImageDescription
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(description);
        byte[] exif = tiff.array();
        int length = 2 + 6 + exif.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** Solo l'intestazione di un PNG enorme (decompression bomb): dimensioni dichiarate, dati assenti. */
    public static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    public static byte[] resource(String name) {
        try (InputStream in = TestImages.class.getResourceAsStream("/media/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Risorsa di test mancante: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BufferedImage read(byte[] data) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean contains(byte[] haystack, String text) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(text);
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
