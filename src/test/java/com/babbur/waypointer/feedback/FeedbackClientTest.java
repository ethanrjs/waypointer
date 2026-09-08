package com.babbur.waypointer.feedback;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackClientTest {
    @Test void submissionsWithoutDebugReportsNeverSetTheAnonymizedFlag() {
        for (String type : List.of("general", "bug")) {
            var submission = new FeedbackSubmission(type, "Feedback without a snapshot", true,
                    "", "", true, List.of());
            assertFalse(submission.anonymized());
            String multipart = body(FeedbackClient.request(submission));
            assertTrue(multipart.contains("name=\"anonymized\"\r\n\r\nfalse\r\n"));
            assertFalse(multipart.contains("name=\"debugReport\""));
        }
    }

    @Test void anonymousMultipartMatchesTheSiteContractWithoutIdentityHeaders() {
        var request = FeedbackClient.request(new FeedbackSubmission("bug", "Hello é\nworld", true,
                "private@example.com", "debug snapshot", true, List.of()));
        String body = body(request);
        assertEquals("https://waypointermod.com/api/feedback", request.uri().toString());
        assertEquals("POST", request.method());
        assertTrue(body.contains("name=\"type\"\r\n\r\nbug\r\n"));
        assertTrue(body.contains("name=\"debugReport\"\r\n\r\ndebug snapshot\r\n"));
        assertTrue(body.contains("name=\"message\"\r\n\r\nHello é\nworld\r\n"));
        assertFalse(body.contains("private@example.com"));
        assertFalse(body.contains("name=\"contact\""));
        assertFalse(body.contains("filename="));
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertTrue(request.headers().firstValue("Cookie").isEmpty());
        assertTrue(request.headers().firstValue("Origin").isEmpty());
    }

    @Test void explicitContactIsIncludedAndOnly201IsSuccess() {
        var submission = new FeedbackSubmission("general", "Hello", false,
                "chosen name", "", false, List.of());
        assertTrue(body(FeedbackClient.request(submission)).contains("name=\"contact\"\r\n\r\nchosen name"));
        new FeedbackClient(request -> CompletableFuture.completedFuture(201)).send(submission).join();
        for (int code : new int[]{200, 302, 400, 413, 429, 503}) {
            AtomicInteger calls = new AtomicInteger();
            var client = new FeedbackClient(request -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(code);
            });
            assertThrows(CompletionException.class, () -> client.send(submission).join());
            assertEquals(1, calls.get(), "No automatic retry, including ambiguous outcomes");
        }
    }

    static String body(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer value) {
                byte[] block = new byte[value.remaining()]; value.get(block); bytes.writeBytes(block);
            }
            @Override public void onError(Throwable error) { done.completeExceptionally(error); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.join();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
