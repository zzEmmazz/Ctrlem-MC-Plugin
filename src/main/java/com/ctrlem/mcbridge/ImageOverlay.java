package com.ctrlem.mcbridge;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Forge 1.20.1 HUD implementation of the original CtrlEm image action.
 * Images are downloaded off-thread and uploaded as a dynamic texture on the client thread.
 */
final class ImageOverlay {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("ctrlem", "overlay/image");
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long FADE_MILLIS = 250L;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CtrlEm image download");
        thread.setDaemon(true);
        return thread;
    });

    private static int latestRequest;
    private static boolean showing;
    private static int imageWidth;
    private static int imageHeight;
    private static long shownAt;
    private static long hideAt;

    private ImageOverlay() {}


    static CompletableFuture<String> show(String link, int seconds) {
        URI uri;
        try { uri = new URI(link.strip()); }
        catch (URISyntaxException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture("that isn't a valid link");
        }
        String refusal = UrlSafety.refusal(uri);
        if (refusal != null) return CompletableFuture.completedFuture(refusal);

        Minecraft mc = Minecraft.getInstance();
        int request = ++latestRequest;
        int maxWidth = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int maxHeight = Math.max(1, mc.getWindow().getGuiScaledHeight());
        return CompletableFuture.supplyAsync(() -> decode(download(uri), maxWidth, maxHeight), LOADER)
            .thenApplyAsync(image -> {
                if (request != latestRequest || mc.player == null) {
                    image.close();
                    return mc.player == null ? "the player left the world" : "replaced by a newer image";
                }
                try {
                    display(mc, image, seconds);
                    return (String) null;
                } catch (RuntimeException failure) {
                    image.close();
                    throw failure;
                }
            }, mc)
            .exceptionally(ImageOverlay::describe);
    }

    private static byte[] download(URI start) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        URI uri = start;
        for (int hop = 0; hop <= 3; hop++) {
            String refusal = UrlSafety.refusal(uri);
            if (refusal == null) refusal = UrlSafety.resolvedRefusal(uri);
            if (refusal != null) throw new ImageProblem(hop == 0 ? refusal : "the link redirected somewhere not allowed: " + refusal);

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ImageProblem("the image took too long to download");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(remaining))
                .header("User-Agent", "CtrlEm-Minecraft")
                .header("Accept", "image/*")
                .GET().build();
            HttpResponse<InputStream> response;
            try { response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ImageProblem("the download was interrupted", exception);
            } catch (IOException exception) {
                throw new ImageProblem("couldn't download the image", exception);
            }
            try (InputStream stream = response.body()) {
                int status = response.statusCode();
                if (REDIRECTS.contains(status)) {
                    String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new ImageProblem("the link redirected nowhere"));
                    try { uri = uri.resolve(new URI(location)); }
                    catch (URISyntaxException | IllegalArgumentException exception) {
                        throw new ImageProblem("the link redirected to an invalid address", exception);
                    }
                    continue;
                }
                if (status != 200) throw new ImageProblem("the image link answered HTTP " + status);
                long claimed = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (claimed > MAX_BYTES) throw new ImageProblem("the image is over 15 MB");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                int n;
                while ((n = stream.read(block)) >= 0) {
                    if (System.nanoTime() > deadline) throw new ImageProblem("the image took too long to download");
                    if (buffer.size() + n > MAX_BYTES) throw new ImageProblem("the image is over 15 MB");
                    buffer.write(block, 0, n);
                }
                if (buffer.size() == 0) throw new ImageProblem("the link returned nothing");
                return buffer.toByteArray();
            } catch (IOException exception) {
                throw new ImageProblem("couldn't read the downloaded image", exception);
            }
        }
        throw new ImageProblem("the link redirected too many times");
    }

    private static NativeImage decode(byte[] bytes, int maxWidth, int maxHeight) {
        try {
            NativeImage original = NativeImage.read(bytes);
            int w = original.getWidth(), h = original.getHeight();
            if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) {
                original.close();
                throw new ImageProblem("image dimensions must be between 1 and 4096 pixels");
            }
            double scale = Math.min(1.0, Math.min((double) maxWidth / w, (double) maxHeight / h));
            if (scale == 1.0) return original;
            int resizedWidth = Math.max(1, (int) Math.round(w * scale));
            int resizedHeight = Math.max(1, (int) Math.round(h * scale));
            NativeImage resized = new NativeImage(resizedWidth, resizedHeight, false);
            try {
                for (int y = 0; y < resizedHeight; y++) {
                    int sourceY = Math.min(h - 1, (int)((long)y * h / resizedHeight));
                    for (int x = 0; x < resizedWidth; x++) {
                        int sourceX = Math.min(w - 1, (int)((long)x * w / resizedWidth));
                        resized.setPixelRGBA(x, y, original.getPixelRGBA(sourceX, sourceY));
                    }
                }
            } catch (RuntimeException exception) {
                resized.close();
                throw exception;
            } finally { original.close(); }
            return resized;
        } catch (IOException exception) {
            throw new ImageProblem("this link doesn't contain a readable image", exception);
        }
    }

    private static void display(Minecraft mc, NativeImage image, int seconds) {
        clear(mc);
        CtrlEmMod.LOGGER.info("Image displayed");
        mc.getTextureManager().register(TEXTURE, new DynamicTexture(image));
        imageWidth = image.getWidth();
        imageHeight = image.getHeight();
        shownAt = System.currentTimeMillis();
        hideAt = shownAt + Math.max(1, seconds) * 1000L;
        showing = true;
    }

    static void onEndTick(Minecraft mc) {
        if (showing && (mc.player == null || System.currentTimeMillis() >= hideAt)) clear(mc);
    }

    private static void clear(Minecraft mc) {
        if (showing) mc.getTextureManager().release(TEXTURE);
        showing = false;
    }

    static void render(GuiGraphics graphics) {
        if (!showing || imageWidth < 1 || imageHeight < 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        double alphaIn = Math.min(1.0, (double) Math.max(0, now - shownAt) / FADE_MILLIS);
        double alphaOut = Math.min(1.0, (double) Math.max(0, hideAt - now) / FADE_MILLIS);
        int a = (int)(255 * Math.min(alphaIn, alphaOut));
        if (a <= 0) return;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        graphics.fill(0, 0, screenWidth, screenHeight, (Math.min(128, a / 2) << 24));
        double scale = Math.min(1, Math.min((double) screenWidth / imageWidth, (double) screenHeight / imageHeight));
        int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
        int x = (screenWidth - drawWidth) / 2;
        int y = (screenHeight - drawHeight) / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, a / 255f);
        try {
            graphics.blit(TEXTURE, x, y, drawWidth, drawHeight, 0f, 0f,
                imageWidth, imageHeight, imageWidth, imageHeight);
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof ImageProblem problem) return problem.getMessage();
        CtrlEmMod.LOGGER.warn("Image overlay failed", cause);
        return "couldn't display that image";
    }

    private static final class ImageProblem extends RuntimeException {
        ImageProblem(String message) { super(message); }
        ImageProblem(String message, Throwable cause) { super(message, cause); }
    }
}
