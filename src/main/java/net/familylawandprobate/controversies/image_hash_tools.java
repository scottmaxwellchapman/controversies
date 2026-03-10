package net.familylawandprobate.controversies;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * Small utility for computing deterministic image hashes used by
 * similarity search and deduplication.
 */
public final class image_hash_tools {

    private image_hash_tools() {
    }

    public static final class HashRec {
        public final int width;
        public final int height;
        public final String sha256Rgb;
        public final String averageHash64;
        public final String differenceHash64;

        public HashRec(int width,
                       int height,
                       String sha256Rgb,
                       String averageHash64,
                       String differenceHash64) {
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.sha256Rgb = safe(sha256Rgb).trim().toLowerCase(Locale.ROOT);
            this.averageHash64 = normalizeHex64(averageHash64);
            this.differenceHash64 = normalizeHex64(differenceHash64);
        }
    }

    public static HashRec compute(BufferedImage image) throws Exception {
        if (image == null) return new HashRec(0, 0, "", "", "");
        int width = Math.max(0, image.getWidth());
        int height = Math.max(0, image.getHeight());
        if (width <= 0 || height <= 0) return new HashRec(0, 0, "", "", "");

        String shaRgb = sha256Rgb(image);
        String ahash64 = averageHash64(image);
        String dhash64 = differenceHash64(image);
        return new HashRec(width, height, shaRgb, ahash64, dhash64);
    }

    public static BufferedImage decodePngBase64(String base64Png) {
        String b64 = safe(base64Png).trim();
        if (b64.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw == null || raw.length == 0) return null;
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (Exception ex) {
            return null;
        }
    }

    public static String normalizeHex64(String raw) {
        String hex = safe(raw).trim().toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (hex.length() == 16) return hex;
        if (hex.length() > 16) return hex.substring(hex.length() - 16);
        if (hex.isBlank()) return "";
        StringBuilder sb = new StringBuilder(16);
        for (int i = hex.length(); i < 16; i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }

    public static int hammingDistance64(String leftHex64, String rightHex64) {
        String a = normalizeHex64(leftHex64);
        String b = normalizeHex64(rightHex64);
        if (a.isBlank() || b.isBlank()) return 64;
        try {
            long av = Long.parseUnsignedLong(a, 16);
            long bv = Long.parseUnsignedLong(b, 16);
            return Long.bitCount(av ^ bv);
        } catch (Exception ex) {
            return 64;
        }
    }

    private static String sha256Rgb(BufferedImage image) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int w = Math.max(0, image.getWidth());
        int h = Math.max(0, image.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                md.update((byte) ((rgb >> 16) & 0xff));
                md.update((byte) ((rgb >> 8) & 0xff));
                md.update((byte) (rgb & 0xff));
            }
        }
        return toHex(md.digest());
    }

    private static String averageHash64(BufferedImage image) {
        BufferedImage gray = toGray(image, 8, 8);
        if (gray == null) return "";

        int[] samples = new int[64];
        long sum = 0L;
        int i = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                samples[i++] = v;
                sum += v;
            }
        }
        double avg = sum / 64.0d;
        long bits = 0L;
        for (int v : samples) {
            bits <<= 1;
            if (v >= avg) bits |= 1L;
        }
        return String.format(Locale.ROOT, "%016x", bits);
    }

    private static String differenceHash64(BufferedImage image) {
        BufferedImage gray = toGray(image, 9, 8);
        if (gray == null) return "";
        long bits = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray.getRaster().getSample(x, y, 0);
                int right = gray.getRaster().getSample(x + 1, y, 0);
                bits <<= 1;
                if (right >= left) bits |= 1L;
            }
        }
        return String.format(Locale.ROOT, "%016x", bits);
    }

    private static BufferedImage toGray(BufferedImage image, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return null;
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
            return gray;
        } finally {
            g.dispose();
        }
    }

    private static String toHex(byte[] raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
