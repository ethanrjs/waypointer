package com.babbur.waypointer.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackImageTest {
    @TempDir Path directory;

    @Test void thumbnailsAreBoundedAndPreserveAspectWithoutUpscaling() throws Exception {
        int[][] cases = {{800, 400, 160, 80}, {400, 800, 50, 100}, {200, 200, 100, 100},
                {7, 3, 7, 3}, {2000, 1, 160, 1}, {1, 2000, 1, 100}};
        for (int[] dimensions : cases) {
            Path path = directory.resolve(dimensions[0] + "x" + dimensions[1] + ".png");
            ImageIO.write(new BufferedImage(dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB), "png", path.toFile());
            FeedbackImage image = FeedbackImage.read(path);
            assertEquals(dimensions[2], image.thumbnailWidth());
            assertEquals(dimensions[3], image.thumbnailHeight());
            assertEquals(dimensions[2] * dimensions[3], image.thumbnailPixels().length);
            BufferedImage upload = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            assertEquals(dimensions[0], upload.getWidth());
            assertEquals(dimensions[1], upload.getHeight());
        }
    }

    @Test void thumbnailPixelsPreservePngAlphaAndCannotBeChangedByCallers() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80123456);
        source.setRGB(1, 0, 0x00000000);
        Path path = directory.resolve("alpha.png");
        ImageIO.write(source, "png", path.toFile());
        FeedbackImage image = FeedbackImage.read(path);
        int[] pixels = image.thumbnailPixels();
        assertArrayEquals(new int[]{0x80123456, 0}, pixels);
        pixels[0] = 0;
        assertEquals(0x80123456, image.thumbnailPixels()[0]);
    }

    @Test void jpegThumbnailsAreOpaqueAndKeepColorChannels() throws Exception {
        BufferedImage source = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally { graphics.dispose(); }
        Path path = directory.resolve("red.jpg");
        ImageIO.write(source, "jpeg", path.toFile());
        FeedbackImage image = FeedbackImage.read(path);
        assertEquals("image/jpeg", image.mimeType());
        assertEquals("jpg", image.extension());
        assertEquals(160, image.thumbnailWidth());
        assertEquals(100, image.thumbnailHeight());
        for (int pixel : image.thumbnailPixels()) {
            assertEquals(255, pixel >>> 24);
            assertTrue(((pixel >>> 16) & 255) >= 250);
            assertTrue(((pixel >>> 8) & 255) <= 5);
            assertTrue((pixel & 255) <= 5);
        }
    }

    @Test void reencodingRemovesMetadataAndMultipartNeverIncludesTheLocalName() throws Exception {
        Path path = directory.resolve("private-player-home.png");
        BufferedImage pixels = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pixels), null);
            var root = new IIOMetadataNode("javax_imageio_png_1.0");
            var text = new IIOMetadataNode("tEXt");
            var entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Author");
            entry.setAttribute("value", "private-person@example.com");
            text.appendChild(entry); root.appendChild(text);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.write(null, new IIOImage(pixels, null, metadata), null);
        } finally { writer.dispose(); }
        assertTrue(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains("private-person"));
        FeedbackImage image = FeedbackImage.read(path);
        assertFalse(new String(image.bytes(), StandardCharsets.ISO_8859_1).contains("private-person"));
        assertEquals("image/png", image.mimeType());
        String multipart = FeedbackClientTest.body(FeedbackClient.request(new FeedbackSubmission("general",
                "Image attached", true, "", "", true, List.of(image))));
        assertTrue(multipart.contains("name=\"images\"; filename=\"image-1.png\""));
        assertFalse(multipart.contains(path.toString()));
        assertFalse(multipart.contains("private-player-home"));
        assertFalse(multipart.contains("private-person"));
    }

    @Test void unsupportedAndOversizedFilesAreRejectedBeforeUpload() throws Exception {
        Path text = directory.resolve("fake.png");
        Files.writeString(text, "not an image");
        assertThrows(IOException.class, () -> FeedbackImage.read(text));
        Path large = directory.resolve("large.png");
        try (var out = new RandomAccessFile(large.toFile(), "rw")) {
            out.setLength(FeedbackSubmission.MAX_IMAGE_BYTES + 1L);
        }
        assertThrows(IOException.class, () -> FeedbackImage.read(large));
    }
}
