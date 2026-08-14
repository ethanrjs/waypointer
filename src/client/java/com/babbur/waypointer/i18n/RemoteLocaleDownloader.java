package com.babbur.waypointer.i18n;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class RemoteLocaleDownloader {
    static final int MAX_BYTES = 256 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final java.util.regex.Pattern TRUSTED_PATH = java.util.regex.Pattern.compile(
            "/ethanrjs/waypointer/[0-9a-f]{40}/translations/lang/[a-z0-9]+(?:_[a-z0-9]+)*\\.json");

    record Response(int status, byte[] body) {}

    interface Transport {
        CompletableFuture<Response> get(URI uri);
    }

    private final Transport transport;

    RemoteLocaleDownloader() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        transport = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return client.sendAsync(request, info -> {
                OptionalLong length = info.headers().firstValueAsLong("Content-Length");
                if (length.isPresent() && length.getAsLong() > MAX_BYTES) {
                    return new RejectingBodySubscriber("Locale download exceeds the size limit");
                }
                return new BoundedBodySubscriber(MAX_BYTES);
            }).thenApply(response -> new Response(response.statusCode(), response.body()));
        };
    }

    RemoteLocaleDownloader(Transport transport) {
        this.transport = transport;
    }

    CompletableFuture<byte[]> download(URI uri) {
        requireTrustedUri(uri);
        CompletableFuture<Response> request = transport.get(uri);
        CompletableFuture<byte[]> result = request.thenApply(response -> {
            if (response.status() != 200) throw new IllegalStateException("Locale download returned HTTP " + response.status());
            if (response.body().length == 0 || response.body().length > MAX_BYTES) {
                throw new IllegalStateException("Locale download has an invalid size");
            }
            return response.body();
        }).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
        result.whenComplete((ignored, failure) -> {
            if (failure instanceof java.util.concurrent.TimeoutException) request.cancel(true);
        });
        return result;
    }

    private static void requireTrustedUri(URI uri) {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"raw.githubusercontent.com".equals(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !TRUSTED_PATH.matcher(uri.getPath()).matches()) {
            throw new IllegalArgumentException("Unsafe locale download URI");
        }
    }

    private static final class RejectingBodySubscriber implements HttpResponse.BodySubscriber<byte[]>, Flow.Subscriber<List<java.nio.ByteBuffer>> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final String message;

        private RejectingBodySubscriber(String message) {
            this.message = message;
        }

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            body.completeExceptionally(new IllegalStateException(message));
        }
        @Override public void onNext(List<java.nio.ByteBuffer> item) {}
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() {}
    }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]>, Flow.Subscriber<List<java.nio.ByteBuffer>> {
        private final int maximum;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int received;

        BoundedBodySubscriber(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            try {
                for (java.nio.ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    if (size > maximum - received) throw new IllegalStateException("Locale download exceeds the size limit");
                    byte[] chunk = new byte[size];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    received += size;
                }
                subscription.request(1);
            } catch (RuntimeException failure) {
                subscription.cancel();
                body.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
