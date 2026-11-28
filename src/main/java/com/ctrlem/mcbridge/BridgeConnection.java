package com.ctrlem.mcbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Unchanged desktop-side contract: 127.0.0.1 + discovery JSON + protocol 1. */
public final class BridgeConnection {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> daemon(task, "CtrlEm bridge writer"));
    private static BufferedWriter out; // Access only on writer executor
    private static String lastProblem;

    private BridgeConnection() {}

    static void start() {
        if (Boolean.getBoolean("ctrlem.bridge.disabled")) {
            CtrlEmMod.LOGGER.info("Bridge disabled with -Dctrlem.bridge.disabled");
            return;
        }
        daemon(BridgeConnection::connectLoop, "CtrlEm bridge").start();
    }

    static void send(JsonObject message) {
        String line = message.toString() + "\n";
        WRITER.execute(() -> {
            if (out == null) return;
            try { out.write(line); out.flush(); }
            catch (IOException e) { out = null; }
        });
    }

    private static void connectLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Discovery discovery = readDiscovery();
            if (discovery != null) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), discovery.port()), 2_000);
                    socket.setTcpNoDelay(true);
                    session(socket, discovery.token());
                } catch (IOException e) {
                    String problem = e.toString();
                    if (!problem.equals(lastProblem)) {
                        CtrlEmMod.LOGGER.info("CtrlEm bridge port {}: {}", discovery.port(), problem);
                        lastProblem = problem;
                    }
                }
            }
            try { Thread.sleep(3_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void session(Socket socket, String token) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("token", token);
        hello.addProperty("protocol", 1);
        writer.write(hello + "\n"); writer.flush();
        socket.setSoTimeout(5_000);
        JsonObject reply = parse(in.readLine());
        String replyType = reply == null ? null : string(reply, "type");
        if ("busy".equals(replyType)) throw new IOException("another Minecraft is already connected");
        if (!"welcome".equals(replyType)) throw new IOException("bridge rejected connection");
        socket.setSoTimeout(0);
        lastProblem = null;
        setWriter(writer);
        CtrlEmMod.LOGGER.info("Connected to local CtrlEm bridge");
        Minecraft.getInstance().execute(StateReporter::resend);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject message = parse(line);
                if (message != null && "command".equals(string(message, "type"))) dispatch(message);
            }
        } finally {
            setWriter(null);
            CtrlEmMod.LOGGER.info("Disconnected from CtrlEm bridge");
        }
    }

    private static void dispatch(JsonObject message) {
        JsonElement idElement = message.get("id");
        String action = string(message, "action");
        if (idElement == null || !idElement.isJsonPrimitive() || action == null) return;
        long id;
        try { id = idElement.getAsLong(); }
        catch (RuntimeException ex) { return; }
        JsonObject args = message.get("args") instanceof JsonObject object ? object : new JsonObject();
        Minecraft.getInstance().execute(() -> {
            CompletableFuture<String> result;
            try { result = Actions.run(action, args); }
            catch (RuntimeException e) { result = CompletableFuture.failedFuture(e); }
            result.whenComplete((error, failure) -> {
                if (failure != null) {
                    CtrlEmMod.LOGGER.error("CtrlEm command {} failed", action, failure);
                    error = "the mod hit an error running this";
                }
                JsonObject response = new JsonObject();
                response.addProperty("type", "result");
                response.addProperty("id", id);
                response.addProperty("ok", error == null);
                if (error != null) response.addProperty("error", error);
                send(response);
            });
        });
    }

    private static void setWriter(BufferedWriter value) { WRITER.execute(() -> out = value); }

    private static Discovery readDiscovery() {
        try {
            JsonObject obj = parse(Files.readString(discoveryPath(), StandardCharsets.UTF_8));
            String token;
            if (obj == null || (token = string(obj, "token")) == null) return null;
            int port = obj.get("port").getAsInt();
            if (port < 1 || port > 65535 || token.isBlank()) return null;
            return new Discovery(port, token);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static Path discoveryPath() {
        String home = System.getenv("LOCALAPPDATA");
        if (home == null || home.isBlank()) home = System.getenv("HOME");
        if (home == null || home.isBlank()) home = System.getProperty("user.home", System.getProperty("java.io.tmpdir"));
        return Path.of(home, "ctrlem-mcbridge", "bridge.json");
    }

    private static JsonObject parse(String line) {
        if (line == null) return null;
        try { return JsonParser.parseString(line) instanceof JsonObject object ? object : null; }
        catch (RuntimeException ex) { return null; }
    }
    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }
    private record Discovery(int port, String token) {}
}
