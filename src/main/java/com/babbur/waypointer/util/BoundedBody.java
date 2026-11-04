package com.babbur.waypointer.util;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximumBytes;
    private final long declaredBytes;
    private final Supplier<? extends RuntimeException> tooLarge;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public static HttpResponse.BodyHandler<byte[]> handler(
            int maximumBytes, Supplier<? extends RuntimeException> tooLarge) {
        return info -> new BoundedBody(maximumBytes,
                info.headers().firstValueAsLong("Content-Length").orElse(-1), tooLarge);
    }

    public BoundedBody(int maximumBytes, Supplier<? extends RuntimeException> tooLarge) {
        this(maximumBytes, -1, tooLarge);
    }

    private BoundedBody(int maximumBytes, long declaredBytes, Supplier<? extends RuntimeException> tooLarge) {
        this.maximumBytes = maximumBytes;
        this.declaredBytes = declaredBytes;
        this.tooLarge = tooLarge;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription next) {
        if (subscription != null) {
            next.cancel();
            return;
        }
        subscription = next;
        if (declaredBytes > maximumBytes) fail();
        else next.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > maximumBytes - output.size()) {
                fail();
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            output.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
        body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        body.complete(output.toByteArray());
    }

    private void fail() {
        subscription.cancel();
        body.completeExceptionally(tooLarge.get());
    }
}
