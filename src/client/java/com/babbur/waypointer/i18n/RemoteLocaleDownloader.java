package com.babbur.waypointer.i18n;

import com.babbur.waypointer.util.BoundedBody;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class RemoteLocaleDownloader {
    static final int MAX_BYTES = 256 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Pattern TRUSTED_PATH = Pattern.compile(
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
            return client.sendAsync(request, BoundedBody.handler(MAX_BYTES,
                            () -> new IllegalStateException("Locale download exceeds the size limit")))
                    .thenApply(response -> new Response(response.statusCode(), response.body()));
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
        }).orTimeout(10, TimeUnit.SECONDS);
        result.whenComplete((ignored, failure) -> {
            if (failure instanceof TimeoutException) request.cancel(true);
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
}
