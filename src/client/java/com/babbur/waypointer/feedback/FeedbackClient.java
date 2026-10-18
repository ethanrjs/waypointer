package com.babbur.waypointer.feedback;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonParser;

public final class FeedbackClient {
    public static final URI ENDPOINT = URI.create("https://waypointermod.com/api/feedback");

    @FunctionalInterface
    interface Transport { CompletableFuture<Integer> send(HttpRequest request); }
    private final Transport transport;

    public FeedbackClient() {
        this(request -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode));
    }

    FeedbackClient(Transport transport) { this.transport = transport; }

    public record Capabilities(boolean available, boolean emailNotifications) {}

    public CompletableFuture<Capabilities> capabilities() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "/capabilities"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        return CompletableFuture.supplyAsync(() -> {
            try {
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    byte[] bytes = body.readNBytes(8193);
                    if (response.statusCode() != 200 || bytes.length > 8192)
                        return new Capabilities(false, false);
                    var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    return new Capabilities(json.get("available").getAsBoolean(),
                            json.get("emailNotifications").getAsBoolean());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Capabilities(false, false);
            }
        });
    }

    public CompletableFuture<Void> send(FeedbackSubmission submission) {
        return CompletableFuture.supplyAsync(() -> request(submission))
                .thenCompose(transport::send).thenAccept(status -> {
                    if (status != 201) throw new IllegalStateException(switch (status) {
                        case 429 -> "Too many submissions. Wait before trying again.";
                        case 413 -> "The submission is too large. Remove an image or the debug report.";
                        case 400, 422 -> "The server rejected the submission. Check the message and attachments.";
                        default -> "Feedback was not confirmed. Your draft is kept. Try again later.";
                    });
                });
    }

    static HttpRequest request(FeedbackSubmission submission) {
        String boundary = "Waypointer-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "type", submission.type());
        field(body, boundary, "message", submission.message());
        field(body, boundary, "anonymous", Boolean.toString(submission.anonymous()));
        field(body, boundary, "anonymized", Boolean.toString(submission.anonymized()));
        if (!submission.anonymous() && !submission.contact().isEmpty())
            field(body, boundary, "contact", submission.contact());
        if (!submission.debugReport().isEmpty())
            field(body, boundary, "debugReport", submission.debugReport());
        for (int i = 0; i < submission.images().size(); i++) {
            FeedbackImage image = submission.images().get(i);
            file(body, boundary, "images", "image-" + (i + 1) + "." + image.extension(),
                    image.mimeType(), image.bytes());
        }
        text(body, "--" + boundary + "--\r\n");
        return HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        text(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        text(body, value + "\r\n");
    }

    private static void file(ByteArrayOutputStream body, String boundary, String name,
                             String filename, String contentType, byte[] bytes) {
        text(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        body.writeBytes(bytes);
        text(body, "\r\n");
    }

    private static void text(ByteArrayOutputStream body, String value) {
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
