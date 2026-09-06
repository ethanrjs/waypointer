package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class StructureRelayConnectionTest {
    private static final URI ENDPOINT = URI.create("wss://example.invalid/structures");

    @Test
    void closingCancelsPendingAndRejectsLateCallbacks() {
        Stub stub = new Stub();
        StructureRelayConnection connection = stub.connection();
        connection.connect(ENDPOINT);
        assertTrue(connection.isConnecting());
        WebSocket.Listener stale = stub.listener;
        connection.close();
        assertTrue(stub.pending.isCancelled());
        stale.onOpen(stub.socket);
        assertEquals(1, stub.aborts);
        assertFalse(connection.isOpen());
        connection.connect(ENDPOINT);
        stub.listener.onOpen(stub.socket);
        stale.onText(stub.socket, "stale", true);
        stale.onError(stub.socket, new IllegalStateException());
        assertTrue(connection.isOpen());
        assertNull(connection.poll());
    }

    @Test
    void boundsFragmentsQueueAndOutstandingSends() {
        Stub stub = new Stub();
        StructureRelayConnection connection = stub.connection();
        connection.connect(ENDPOINT);
        stub.listener.onOpen(stub.socket);
        stub.listener.onText(stub.socket, "hel", false);
        assertNull(connection.poll());
        stub.listener.onText(stub.socket, "lo", true);
        assertEquals("hello", connection.poll());
        assertTrue(connection.send("one"));
        assertFalse(connection.send("two"));
        stub.sent.complete(stub.socket);
        assertTrue(connection.send("two"));
        for (int i = 0; i < 33; i++) stub.listener.onText(stub.socket, "message", true);
        assertFalse(connection.isOpen());
        assertNull(connection.poll());

        connection.connect(ENDPOINT);
        stub.listener.onOpen(stub.socket);
        stub.listener.onText(stub.socket, "a".repeat(16 * 1024), false);
        stub.listener.onText(stub.socket, "b", true);
        assertFalse(connection.isOpen());

        connection.connect(ENDPOINT);
        stub.listener.onOpen(stub.socket);
        stub.listener.onText(stub.socket, "\u20ac".repeat(6 * 1024), true);
        assertFalse(connection.isOpen());
    }

    private static final class Stub {
        WebSocket.Listener listener;
        CompletableFuture<WebSocket> pending;
        CompletableFuture<WebSocket> sent = new CompletableFuture<>();
        int aborts;
        final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if (method.getName().equals("abort")) aborts++;
                    if (method.getName().equals("sendText")) return sent;
                    return null;
                });

        StructureRelayConnection connection() {
            return new StructureRelayConnection((uri, receiver) -> {
                listener = receiver;
                pending = new CompletableFuture<>();
                return pending;
            });
        }
    }
}
