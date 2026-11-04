package com.babbur.waypointer.util;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedBodyTest {

    @Test
    void collectsABodyUpToTheLimit() {
        BoundedBody body = new BoundedBody(4, TooLarge::new);
        TestSubscription subscription = new TestSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        body.onComplete();

        assertFalse(subscription.cancelled);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().join());
    }

    @Test
    void cancelsBeforeBufferingPastTheLimit() {
        BoundedBody body = new BoundedBody(3, TooLarge::new);
        TestSubscription subscription = new TestSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));

        assertTrue(subscription.cancelled);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> body.getBody().toCompletableFuture().join());
        assertInstanceOf(TooLarge.class, failure.getCause());
    }

    @Test
    void rejectsADeclaredLengthOverTheLimitWithoutRequestingData() {
        HttpResponse.BodySubscriber<byte[]> body = BoundedBody.handler(8, TooLarge::new)
                .apply(responseWithLength(9));
        TestSubscription subscription = new TestSubscription();
        body.onSubscribe(subscription);

        assertTrue(subscription.cancelled);
        assertFalse(subscription.requested);
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    private static HttpResponse.ResponseInfo responseWithLength(long length) {
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Length", List.of(Long.toString(length))),
                (name, value) -> true);
        return new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return 200; }
            @Override public HttpHeaders headers() { return headers; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class TooLarge extends RuntimeException {
    }

    private static final class TestSubscription implements Flow.Subscription {
        private boolean cancelled;
        private boolean requested;
        @Override public void request(long count) { requested = true; }
        @Override public void cancel() { cancelled = true; }
    }
}
