package com.ctrlem.mcbridge;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.client.gui.OverlayRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * "Show an image": downloads a picture from a link and covers the whole game screen with it —
 * black backdrop, image fitted in — for a few seconds.
 *
 * <p>The link comes from whoever sent the command, so the download follows the same rules as the
 * CtrlEm client's own popupImage (see {@link UrlSafety}) plus caps on size, dimensions and time.
 * The image's host does learn the player's IP address, as it would for popupImage.
 *
 * <p>Extends {@link GuiComponent} only to reach its protected static {@code fill} and {@code blit}
 * helpers: 1.18.2 draws through a PoseStack and raw RenderSystem state rather than the
 * GuiGraphics object newer versions hand to a layer.
 */
@Mod.EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class ImageOverlay extends GuiComponent {
    private static final String LAYER = "ctrlem_image";
    // The two-argument constructor is deprecated for removal in 1.18.2; this is the same thing.
    private static final ResourceLocation TEXTURE = new ResourceLocation(CtrlEmMod.MODID + ":overlay/image");

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
            // A fresh body handler per hop, so a redirect's own body doesn't count toward the next.
            SizeLimitedBody body = new SizeLimitedBody();
            CompletableFuture<HttpResponse<Void>> pending = HTTP.sendAsync(request, body);
            HttpResponse<Void> response;
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
                // The size cap throws from inside the body subscriber, which reaches here wrapped
                // in however many layers the HTTP client felt like adding.
                for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof ImageException) throw (ImageException) cause;
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
            byte[] bytes = body.bytes();
            if (bytes.length == 0) throw new ImageException("the link returned nothing");
            return bytes;
        }
        throw new ImageException("the link redirected too many times");
    }

    /**
     * Caps a download at {@link #MAX_BYTES}. Newer Java has {@code BodyHandlers.limiting} for
     * this; 1.18.2 runs on Java 17, which predates it, so the declared length is refused up front
     * and the bytes are counted as they arrive in case the header lied or was absent.
     */
    private static final class SizeLimitedBody implements HttpResponse.BodyHandler<Void> {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo info) {
            info.headers().firstValueAsLong("Content-Length").ifPresent(declared -> {
                if (declared > MAX_BYTES) throw tooBig();
            });
            return HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(this::append));
        }

        private void append(byte[] bytes) {
            if (buffer.size() + bytes.length > MAX_BYTES) throw tooBig();
            buffer.write(bytes, 0, bytes.length);
        }

        byte[] bytes() {
            return buffer.toByteArray();
        }
    }

    private static ImageException tooBig() {
        return new ImageException("the image is over " + MAX_BYTES / (1024 * 1024) + " MB");
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
                    // setPixelRGBA is 1.18.2's name for it; the packing is ABGR all the same.
                    out.setPixelRGBA(x, y, (int) (a / n) << 24 | (int) (b / n) << 16 | (int) (g / n) << 8 | (int) (r / n));
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
        mc.getTextureManager().register(TEXTURE, new DynamicTexture(image));
        width = image.getWidth();
        height = image.getHeight();
        shownAt = Util.getMillis();
        hideAt = shownAt + seconds * 1000L;
        showing = true;
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof ImageException) return cause.getMessage();
        CtrlEmMod.LOGGER.warn("CtrlEm couldn't show an image", cause);
        return "couldn't load that image";
    }

    /** Called once from client setup; 1.18.2 has no registration event for HUD layers. */
    static void registerLayer() {
        OverlayRegistry.registerOverlayTop(LAYER, ImageOverlay::render);
    }

    /** Frees the texture on time even while the HUD isn't being drawn (title screen, F1). */
    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (showing && (Util.getMillis() >= hideAt || Minecraft.getInstance().player == null)) {
            showing = false;
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
        }
    }

    private static void render(ForgeIngameGui gui, PoseStack poseStack, float partialTick, int screenWidth, int screenHeight) {
        if (!showing) return;
        long now = Util.getMillis();
        if (now >= hideAt) return; // the tick handler frees it
        float fade = Math.min(1.0F, Math.min(now - shownAt, hideAt - now) / (float) FADE_MILLIS);
        int alpha = Math.round(fade * 255) << 24;

        // fill() sets up and tears down its own blend state, so the backdrop goes first and the
        // state for the image is set afterwards.
        fill(poseStack, 0, 0, screenWidth, screenHeight, alpha); // black backdrop

        float scale = Math.min((float) screenWidth / width, (float) screenHeight / height);
        int drawWidth = Math.round(width * scale);
        int drawHeight = Math.round(height * scale);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, fade);
        blit(poseStack, (screenWidth - drawWidth) / 2, (screenHeight - drawHeight) / 2,
                drawWidth, drawHeight, 0.0F, 0.0F, width, height, width, height);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
