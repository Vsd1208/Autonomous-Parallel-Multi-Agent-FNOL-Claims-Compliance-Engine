package com.guidewire.fnol.api;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Difference hash (dHash) of a photo: 64 bits that stay the same, or nearly, when the photo is resized,
 * recompressed or slightly brightened, so a photo reused from an earlier claim (or the internet) is recognised
 * even when it is not byte-identical. Two hashes within {@link #MAX_DISTANCE} bits are treated as the same photo.
 */
public final class PerceptualHash {

    /** Differing bits allowed for "the same photo" (of 64). */
    public static final int MAX_DISTANCE = 5;
    static final int MIN_SIDE = 32;
    static final int MIN_BITS = 8;

    private PerceptualHash() {
    }

    /** 16 hex characters, or null when the image cannot be decoded (e.g. WebP, which ImageIO does not read). */
    public static String of(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (image == null || image.getWidth() < MIN_SIDE || image.getHeight() < MIN_SIDE) {
            return null;   // too small to fingerprint; identical files are still caught by SHA-256
        }
        // 9 x 8 greyscale; each bit says whether a pixel is brighter than its right-hand neighbour.
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, 9, 8, null);
        g.dispose();
        long bits = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = small.getRaster().getSample(x, y, 0);
                int right = small.getRaster().getSample(x + 1, y, 0);
                bits = (bits << 1) | (left > right ? 1 : 0);
            }
        }
        // A flat image (a blank wall, a black frame) hashes to almost all zeros and would "match" every other flat
        // image, so it gets no fingerprint.
        if (Long.bitCount(bits) < MIN_BITS || Long.bitCount(bits) > 64 - MIN_BITS) {
            return null;
        }
        return String.format("%016x", bits);
    }

    /** Number of differing bits between two hashes. */
    public static int distance(String a, String b) {
        return Long.bitCount(Long.parseUnsignedLong(a, 16) ^ Long.parseUnsignedLong(b, 16));
    }
}
