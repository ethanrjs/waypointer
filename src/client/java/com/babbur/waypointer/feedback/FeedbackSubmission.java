package com.babbur.waypointer.feedback;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record FeedbackSubmission(String type, String message, boolean anonymous,
                                 String contact, String debugReport, boolean anonymized,
                                 List<FeedbackImage> images) {
    public static final int MAX_MESSAGE = 10_000;
    public static final int MAX_IMAGES = 4;
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TOTAL_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;

    public FeedbackSubmission {
        if (!"bug".equals(type) && !"general".equals(type))
            throw new IllegalArgumentException("Choose bug report or general feedback.");
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE)
            throw new IllegalArgumentException("Enter between 1 and 10,000 characters.");
        contact = anonymous ? "" : contact == null ? "" : contact.strip();
        if (contact.length() > 200 || contact.contains("\r") || contact.contains("\n"))
            throw new IllegalArgumentException("Contact must be one line of up to 200 characters.");
        debugReport = debugReport == null ? "" : debugReport;
        if (!"bug".equals(type) && !debugReport.isEmpty())
            throw new IllegalArgumentException("Debug reports can only be attached to bug reports.");
        anonymized = !debugReport.isEmpty() && anonymized;
        if (debugReport.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES)
            throw new IllegalArgumentException("The debug report is too large. Remove it to send feedback.");
        images = List.copyOf(images);
        if (images.size() > MAX_IMAGES || images.stream().mapToLong(FeedbackImage::size).sum() > MAX_TOTAL_IMAGE_BYTES)
            throw new IllegalArgumentException("Use up to 4 images, with a combined size of 10 MiB or less.");
    }
}
