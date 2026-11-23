package com.ctrlem.mcbridge;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * "Show an image": downloads a picture from a link and covers the whole game screen with it —
 * black backdrop, image fitted in — for a few seconds.
 *
 * <p>The link comes from whoever sent the command, so the download follows the same rules as the
 * CtrlEm client's own popupImage (see {@link UrlSafety}) plus caps on size, dimensions and time.
 * The image's host does learn the player's IP address, as it would for popupImage.
 */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
final class ImageOverlay {
    private static final Identifier LAYER = Identifier.fromNamespaceAndPath(CtrlEmMod.MODID, "image_overlay");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(CtrlEmMod.MODID, "overlay/image");

    /** CtrlEm's own uploads are capped at 15 MB, so nothing legitimate is bigger. */
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    /** Checked from the header before decoding, so a small file claiming huge dimensions can't eat memory. */
    private static final int MAX_DIMENSION = 4096;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long FADE_MILLIS = 250;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    /** Redirects are followed by hand, so every hop goes through the same checks as the first. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CtrlEm image loader");
        t.setDaemon(true);
        return t;
    });

    // Game thread only.
    private static int latestRequest;
    private static boolean showing;
    private static int width;
    private static int height;
    private static long shownAt;
    private static long hideAt;

    private ImageOverlay() {}

    /** A failure worded for the command's sender. */
    private static final class ImageException extends RuntimeException {
        ImageException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Starts loading {@code link}; completes with {@code null} once the image is on screen, or with
     * the reason it isn't. A newer image replaces one still loading or showing.
     */
    static CompletableFuture<String> show(String link, int seconds) {
        URI uri;
        try {
            uri = new URI(link.strip());
        } catch (URISyntaxException e) {
            return CompletableFuture.completedFuture("that isn't a valid link");
        }
        String refusal = UrlSafety.refusal(uri);
        if (refusal != null) return CompletableFuture.completedFuture(refusal);

        Minecraft mc = Minecraft.getInstance();
        int request = ++latestRequest;
        int maxWidth = mc.getWindow().getWidth();
        int maxHeight = mc.getWindow().getHeight();
        return CompletableFuture
                .supplyAsync(() -> decode(download(uri), maxWidth, maxHeight), LOADER)
                .thenApplyAsync(image -> {
                    if (request != latestRequest || mc.player == null) {
                        image.close();
                        return mc.player == null ? "the player left the world" : "replaced by a newer image";
                    }
                    display(mc, image, seconds);
                    return (String) null;
                }, mc)
                .exceptionally(ImageOverlay::describe);
    }

    private static byte[] download(URI start) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        URI uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String refusal = hop == 0 ? null : UrlSafety.refusal(uri);
            if (refusal == null) refusal = UrlSafety.resolvedRefusal(uri);
            if (refusal != null) throw new ImageException(hop == 0 ? refusal : "the link redirected somewhere not allowed: " + refusal);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "CtrlEm-Minecraft")
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<byte[]>> pending =
                    HTTP.sendAsync(request, BodyHandlers.limiting(BodyHandlers.ofByteArray(), MAX_BYTES));
            HttpResponse<byte[]> response;
            try {
                // The request timeout only covers the headers; this bounds the body too.
                response = pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new ImageException("the image took too long to download");
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new ImageException("the download was interrupted");
            } catch (ExecutionException e) {
                String message = String.valueOf(e.getCause() == null ? null : e.getCause().getMessage());
                if (e.getCause() instanceof IOException && message.contains("exceed")) {
                    throw new ImageException("the image is over " + MAX_BYTES / (1024 * 1024) + " MB");
                }
                throw new ImageException("couldn't download the image");
            }

            int status = response.statusCode();
            if (REDIRECTS.contains(status)) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new ImageException("the link redirected nowhere"));
                try {
                    uri = uri.resolve(new URI(location));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    throw new ImageException("the link redirected to an invalid address");
                }
                continue;
            }
            if (status != 200) throw new ImageException("the image link answered HTTP " + status);
            if (response.body().length == 0) throw new ImageException("the link returned nothing");
            return response.body();
        }
        throw new ImageException("the link redirected too many times");
    }

    /** Decodes with the stb library the game already ships: PNG, JPEG, BMP, and a GIF's first frame. */
    private static NativeImage decode(byte[] bytes, int maxWidth, int maxHeight) {
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded.put(bytes).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(encoded, w, h, channels)) {
                throw new ImageException("that link isn't a PNG, JPEG, GIF or BMP image");
            }
            if (w.get(0) > MAX_DIMENSION || h.get(0) > MAX_DIMENSION) {
                throw new ImageException("the image is over " + MAX_DIMENSION + " pixels across");
            }
            ByteBuffer rgba = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (rgba == null) throw new ImageException("couldn't read that image");
            try {
                return fit(rgba, w.get(0), h.get(0), maxWidth, maxHeight);
            } finally {
                STBImage.stbi_image_free(rgba);
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /**
     * Shrinks the image to fit the game window — never enlarges it; drawing does that — averaging
     * each block of source pixels, so a big photo isn't reduced to noise.
     */
    private static NativeImage fit(ByteBuffer rgba, int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            // Minimised window: the framebuffer is 0×0. Size for a typical screen instead.
            maxWidth = 1920;
            maxHeight = 1080;
        }
        double scale = Math.min(1.0, Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight));
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        NativeImage out = new NativeImage(w, h, false);
        try {
            for (int y = 0; y < h; y++) {
                int y0 = (int) ((long) y * sourceHeight / h);
                int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sourceHeight / h));
                for (int x = 0; x < w; x++) {
                    int x0 = (int) ((long) x * sourceWidth / w);
                    int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sourceWidth / w));
                    long r = 0, g = 0, b = 0, a = 0;
                    for (int sy = y0; sy < y1; sy++) {
                        int row = sy * sourceWidth * 4;
                        for (int sx = x0; sx < x1; sx++) {
                            int i = row + sx * 4;
                            r += rgba.get(i) & 0xff;
                            g += rgba.get(i + 1) & 0xff;
                            b += rgba.get(i + 2) & 0xff;
                            a += rgba.get(i + 3) & 0xff;
                        }
                    }
                    int n = (y1 - y0) * (x1 - x0);
                    out.setPixelABGR(x, y, (int) (a / n) << 24 | (int) (b / n) << 16 | (int) (g / n) << 8 | (int) (r / n));
                }
            }
            return out;
        } catch (RuntimeException e) {
            out.close();
            throw e;
        }
    }

    private static void display(Minecraft mc, NativeImage image, int seconds) {
        // Registering under the same name closes the previous image's texture.
        mc.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "CtrlEm overlay image", image));
        width = image.getWidth();
        height = image.getHeight();
        shownAt = Util.getMillis();
        hideAt = shownAt + seconds * 1000L;
        showing = true;
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof ImageException e) return e.getMessage();
        CtrlEmMod.LOGGER.warn("CtrlEm couldn't show an image", cause);
        return "couldn't load that image";
    }

    @SubscribeEvent
    static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, ImageOverlay::render);
    }

    /** Frees the texture on time even while the HUD isn't being drawn (title screen, F1). */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (showing && (Util.getMillis() >= hideAt || Minecraft.getInstance().player == null)) {
            showing = false;
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
        }
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!showing) return;
        long now = Util.getMillis();
        if (now >= hideAt) return; // the tick handler frees it
        float fade = Math.min(1.0F, Math.min(now - shownAt, hideAt - now) / (float) FADE_MILLIS);
        int alpha = Math.round(fade * 255) << 24;

        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        graphics.fill(0, 0, screenWidth, screenHeight, alpha); // black backdrop
        float scale = Math.min((float) screenWidth / width, (float) screenHeight / height);
        int drawWidth = Math.round(width * scale);
        int drawHeight = Math.round(height * scale);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE,
                (screenWidth - drawWidth) / 2, (screenHeight - drawHeight) / 2, 0, 0,
                drawWidth, drawHeight, width, height, width, height, alpha | 0xFFFFFF);
    }
}
