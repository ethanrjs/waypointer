package com.babbur.waypointer.crystal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/** Bounded socket transport; callers drain messages on the game thread. */
public final class StructureRelayConnection {
    private static final int MAX_MESSAGE = 16 * 1024;
    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(32);
    private final BiFunction<URI, WebSocket.Listener, CompletableFuture<WebSocket>> connector;
    private CompletableFuture<WebSocket> pending;
    private WebSocket socket;
    private long generation;
    private boolean connecting;
    private boolean sending;

    public StructureRelayConnection() {
        this((uri, listener) -> ClientHolder.CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener));
    }

    StructureRelayConnection(BiFunction<URI, WebSocket.Listener, CompletableFuture<WebSocket>> connector) {
        this.connector = connector;
    }

    public synchronized void connect(URI uri) {
        close();
        long attempt = generation;
        connecting = true;
        try {
            pending = connector.apply(uri, new Receiver(attempt));
            pending.whenComplete((opened, error) -> {
                synchronized (StructureRelayConnection.this) {
                    if (attempt != generation) {
                        if (opened != null) opened.abort();
                    } else if (error != null) {
                        close();
                    }
                }
            });
        } catch (RuntimeException error) {
            close();
        }
    }

    public synchronized void close() {
        generation++;
        connecting = false;
        sending = false;
        WebSocket previous = socket;
        CompletableFuture<WebSocket> previousPending = pending;
        socket = null;
        pending = null;
        messages.clear();
        if (previousPending != null) previousPending.cancel(true);
        if (previous != null) previous.abort();
    }

    public synchronized boolean isOpen() {
        return socket != null;
    }

    public synchronized boolean isConnecting() {
        return connecting;
    }

    public synchronized String poll() {
        return messages.poll();
    }

    public synchronized boolean send(String message) {
        if (socket == null || sending || message.length() > MAX_MESSAGE
                || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE) return false;
        long attempt = generation;
        sending = true;
        try {
            socket.sendText(message, true).whenComplete((sent, error) -> {
                synchronized (StructureRelayConnection.this) {
                    if (attempt != generation) return;
                    sending = false;
                    if (error != null) close();
                }
            });
            return true;
        } catch (RuntimeException error) {
            close();
            return false;
        }
    }

    private final class Receiver implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder text = new StringBuilder();

        private Receiver(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket opened) {
            synchronized (StructureRelayConnection.this) {
                if (attempt != generation) {
                    opened.abort();
                    return;
                }
                socket = opened;
                connecting = false;
                opened.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket source, CharSequence data, boolean last) {
            synchronized (StructureRelayConnection.this) {
                if (attempt != generation || source != socket) return null;
                if (data.length() > MAX_MESSAGE - text.length()) {
                    close();
                    return null;
                }
                text.append(data);
                if (last) {
                    String message = text.toString();
                    text.setLength(0);
                    if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE
                            || !messages.offer(message)) {
                        close();
                        return null;
                    }
                }
                source.request(1);
                return null;
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket source, java.nio.ByteBuffer data, boolean last) {
            synchronized (StructureRelayConnection.this) {
                if (attempt == generation) close();
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket source, int status, String reason) {
            synchronized (StructureRelayConnection.this) {
                if (attempt == generation) close();
            }
            return null;
        }

        @Override
        public void onError(WebSocket source, Throwable error) {
            synchronized (StructureRelayConnection.this) {
                if (attempt == generation) close();
            }
        }
    }

    private static final class ClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
