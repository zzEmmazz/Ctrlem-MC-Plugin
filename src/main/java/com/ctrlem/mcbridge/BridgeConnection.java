package com.ctrlem.mcbridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * The link to the CtrlEm "mcbridge" plugin. The bridge listens on a local port and writes that
 * port, plus a per-run token, to a discovery file; this class keeps trying to connect to it, so
 * Minecraft and CtrlEm can be started and restarted in any order.
 *
 * <p>Wire protocol, one JSON object per line (the bridge's main.rs has the authoritative copy):
 * <pre>
 *   mod    → bridge  {"type":"hello","token":"…","protocol":1}
 *   bridge → mod     {"type":"welcome","protocol":1}   or  {"type":"busy"} if another game is connected
 *   bridge → mod     {"type":"command","id":7,"action":"jump","args":{…}}
 *   mod    → bridge  {"type":"result","id":7,"ok":true}  or  {…,"ok":false,"error":"…"}
 *   mod    → bridge  {"type":"state","state":{…}}
 * </pre>
 */
public final class BridgeConnection {
    private static final int PROTOCOL = 1;
    private static final long RETRY_MILLIS = 3000;
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 5000;
    /**
     * The bridge binds 127.0.0.1 specifically. Not {@link InetAddress#getLoopbackAddress()}: Minecraft
     * runs with {@code java.net.preferIPv6Addresses=system}, which makes that {@code ::1} on Windows.
     */
    private static final InetAddress BRIDGE_HOST = ipv4Loopback();

    /**
     * All writes go through this one thread, so the game thread never blocks on the socket and
     * lines from different threads can't interleave.
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> daemon(r, "CtrlEm bridge writer"));

    /** Only touched on the writer thread. Null while disconnected. */
    private static Writer out;

    /**
     * Connect thread only. Each distinct problem is logged once, so a missing bridge doesn't spam
     * the log every few seconds; cleared once a connection gets through.
     */
    private static String lastProblem;

    private BridgeConnection() {}

    static void start() {
        // For a second dev client that should play alongside without being controlled (runLeader).
        if (Boolean.getBoolean("ctrlem.bridge.disabled")) {
            CtrlEmMod.LOGGER.info("CtrlEm bridge connection disabled by -Dctrlem.bridge.disabled");
            return;
        }
        daemon(BridgeConnection::connectLoop, "CtrlEm bridge").start();
    }

    /** Queues one line for the bridge; silently dropped while disconnected. */
    static void send(JsonObject message) {
        String line = message + "\n";
        WRITER.execute(() -> {
            if (out == null) return;
            try {
                out.write(line);
                out.flush();
            } catch (IOException e) {
                // The reader side notices the broken socket and reconnects.
                out = null;
            }
        });
    }

    private static void connectLoop() {
        while (true) {
            Discovery discovery = readDiscovery();
            if (discovery != null) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(BRIDGE_HOST, discovery.port()), CONNECT_TIMEOUT_MILLIS);
                    socket.setTcpNoDelay(true);
                    session(socket, discovery.token());
                } catch (IOException e) {
                    // Bridge not running, restarted, or went away mid-session — keep retrying.
                    String problem = e.toString();
                    if (!problem.equals(lastProblem)) {
                        CtrlEmMod.LOGGER.info("Can't connect to the CtrlEm bridge on port {}: {}", discovery.port(), problem);
                        lastProblem = problem;
                    }
                }
            }
            try {
                Thread.sleep(RETRY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Runs one connection to completion. */
    private static void session(Socket socket, String token) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Writer writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("token", token);
        hello.addProperty("protocol", PROTOCOL);
        writer.write(hello + "\n");
        writer.flush();

        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        JsonObject reply = parse(in.readLine());
        String replyType = reply == null ? null : string(reply, "type");
        // Thrown rather than returned so connectLoop logs it once and keeps retrying: the other
        // game may close, and the bridge may restart with a new token.
        if ("busy".equals(replyType)) throw new IOException("another Minecraft is already connected to it");
        if (!"welcome".equals(replyType)) throw new IOException("it turned the connection down");
        socket.setSoTimeout(0);
        lastProblem = null;

        setWriter(writer);
        CtrlEmMod.LOGGER.info("Connected to the CtrlEm bridge");
        // The bridge forgets game state whenever the link drops, so tell it where we are again.
        Minecraft.getInstance().execute(StateReporter::resend);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject message = parse(line);
                if (message != null && "command".equals(string(message, "type"))) {
                    dispatch(message);
                }
            }
        } finally {
            setWriter(null);
            CtrlEmMod.LOGGER.info("Disconnected from the CtrlEm bridge");
        }
    }

    private static void dispatch(JsonObject message) {
        JsonElement idElement = message.get("id");
        String action = string(message, "action");
        if (idElement == null || !idElement.isJsonPrimitive() || action == null) return;
        long id = idElement.getAsLong();
        JsonObject args = message.get("args") instanceof JsonObject o ? o : new JsonObject();

        // Everything that touches the game happens on the game thread.
        Minecraft.getInstance().execute(() -> {
            CompletableFuture<String> outcome;
            try {
                outcome = Actions.run(action, args);
            } catch (RuntimeException e) {
                outcome = CompletableFuture.failedFuture(e);
            }
            outcome.whenComplete((error, failure) -> {
                if (failure != null) {
                    CtrlEmMod.LOGGER.error("CtrlEm command {} failed", action, failure);
                    error = "the mod hit an error running this";
                }
                // The sender sees this result on CtrlEm; the log is where the player (or a
                // developer) can see what happened and why.
                CtrlEmMod.LOGGER.info("CtrlEm command {}: {}", action, error == null ? "ok" : error);
                JsonObject result = new JsonObject();
                result.addProperty("type", "result");
                result.addProperty("id", id);
                result.addProperty("ok", error == null);
                if (error != null) result.addProperty("error", error);
                send(result);
            });
        });
    }

    private static void setWriter(Writer writer) {
        WRITER.execute(() -> out = writer);
    }

    private record Discovery(int port, String token) {}

    private static Discovery readDiscovery() {
        try {
            JsonObject json = parse(Files.readString(discoveryPath()));
            if (json == null || !json.has("port") || string(json, "token") == null) return null;
            return new Discovery(json.get("port").getAsInt(), string(json, "token"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Must match {@code discovery_path()} in the bridge's main.rs. */
    private static Path discoveryPath() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null) base = System.getenv("HOME");
        if (base == null) base = System.getProperty("java.io.tmpdir");
        return Path.of(base, "ctrlem-mcbridge", "bridge.json");
    }

    private static JsonObject parse(String line) {
        if (line == null) return null;
        try {
            return JsonParser.parseString(line) instanceof JsonObject o ? o : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new AssertionError(e); // only thrown for a wrong-length address
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
