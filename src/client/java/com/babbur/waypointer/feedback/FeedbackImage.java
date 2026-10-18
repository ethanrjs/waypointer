package com.babbur.waypointer.feedback;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class FeedbackImage {
    private final byte[] bytes;
    private final String mimeType;
    private final int thumbnailWidth;
    private final int thumbnailHeight;
    private final int[] thumbnailPixels;

    private FeedbackImage(byte[] bytes, String mimeType, BufferedImage image) {
        this.bytes = bytes;
        this.mimeType = mimeType;
        double scale = Math.min(1.0, Math.min(160.0 / image.getWidth(), 100.0 / image.getHeight()));
        thumbnailWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        thumbnailHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(thumbnailWidth, thumbnailHeight, BufferedImage.TYPE_INT_ARGB);
        var graphics = thumbnail.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, thumbnailWidth, thumbnailHeight, null);
        } finally {
            graphics.dispose();
        }
        thumbnailPixels = thumbnail.getRGB(0, 0, thumbnailWidth, thumbnailHeight, null, 0, thumbnailWidth);
    }

    public static FeedbackImage read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > FeedbackSubmission.MAX_IMAGE_BYTES)
            throw new IOException("Choose a PNG or JPEG file of 5 MiB or less.");
        try (var stream = Files.newInputStream(path)) {
            byte[] source = stream.readNBytes(FeedbackSubmission.MAX_IMAGE_BYTES + 1);
            if (source.length > FeedbackSubmission.MAX_IMAGE_BYTES)
                throw new IOException("The image is larger than 5 MiB.");
            try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(source))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Choose a PNG or JPEG image.");
                ImageReader reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg"))
                        throw new IOException("Only PNG and JPEG images are supported.");
                    reader.setInput(input, true, true);
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels <= 0 || pixels > 20_000_000)
                        throw new IOException("Use an image with no more than 20 million pixels.");
                    var image = reader.read(0);
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, format, encoded)) throw new IOException("The image could not be read.");
                    if (encoded.size() > FeedbackSubmission.MAX_IMAGE_BYTES)
                        throw new IOException("The processed image is larger than 5 MiB. Use a smaller image.");
                    return new FeedbackImage(encoded.toByteArray(), "image/" + format, image);
                } finally {
                    reader.dispose();
                }
            }
        }
    }

    public int size() { return bytes.length; }
    public String mimeType() { return mimeType; }
    public String extension() { return mimeType.equals("image/png") ? "png" : "jpg"; }
    public int thumbnailWidth() { return thumbnailWidth; }
    public int thumbnailHeight() { return thumbnailHeight; }
    public int[] thumbnailPixels() { return thumbnailPixels.clone(); }
    byte[] bytes() { return bytes; }
}
