// Test-only HTTP server: serves fixed bytes per path, honours "Range: bytes=N-", and can be
// told to misbehave (drop the connection early, ignore ranges) so the downloader's failure
// handling runs against a real socket.
package com.example.aikeyboard.models;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RangeServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Map<String, byte[]> files = new HashMap<>();
    /** One entry per request: "path" or "path Range: bytes=N-". */
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    /** If >= 0, the connection is cut after this many body bytes. */
    volatile int dropAfterBytes = -1;
    volatile boolean ignoreRange;

    RangeServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "range-server");
        thread.setDaemon(true);
        thread.start();
    }

    void put(String path, byte[] body) {
        files.put(path, body);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/";
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                handle(client);
            } catch (IOException e) {
                // A dropped or closed connection is part of what the tests exercise.
            }
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String path = requestLine.split(" ")[1].substring(1);
        long from = 0;
        String rangeHeader = null;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("range:")) {
                rangeHeader = line.substring(6).trim();
                from = Long.parseLong(rangeHeader.replace("bytes=", "").replace("-", ""));
            }
        }
        requests.add(rangeHeader == null ? path : path + " Range: " + rangeHeader);

        OutputStream out = client.getOutputStream();
        byte[] body = files.get(path);
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        boolean partial = rangeHeader != null && !ignoreRange;
        int start = partial ? (int) from : 0;
        if (start > body.length) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        int length = body.length - start;
        StringBuilder head = new StringBuilder();
        head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        if (partial) {
            head.append("Content-Range: bytes ").append(start).append('-')
                    .append(body.length - 1).append('/').append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        int send = dropAfterBytes >= 0 ? Math.min(length, dropAfterBytes) : length;
        out.write(body, start, send);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
