package com.replaytr;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReplayModClient implements ClientModInitializer {
    public static final String ID = "replaytr";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static final Recorder RECORDER = new Recorder();
    public static final Playback PLAYBACK = new Playback();

    /** Dosya işleri (okuma, dışa aktarma, silme, ayar kaydı) için tek arka plan thread'i. */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ReplayTR-IO");
        t.setDaemon(true);
        return t;
    });

    public static KeyBinding MENU_KEY;
    public static volatile double speed = 1.0;

    @Override
    public void onInitializeClient() {
        loadConfig();

        MENU_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.replaytr.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.replaytr"));

        ClientTickEvents.START_CLIENT_TICK.register(PLAYBACK::preTick);

        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            RECORDER.tick(c);
            PLAYBACK.tick(c);
            while (MENU_KEY.wasPressed()) {
                if (c.currentScreen == null) c.setScreen(new ReplayScreen());
            }
        });

        // Sohbet ve sistem mesajlarını kayda ekle
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) RECORDER.onMessage(message);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> RECORDER.onMessage(message));

        // Sunucudan çıkınca kaydı kapat, oynatmayı sıfırla
        ClientPlayConnectionEvents.DISCONNECT.register((handler, c) -> {
            RECORDER.stop();
            PLAYBACK.reset(c);
        });

        // Ekranda küçük durum göstergesi
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.options.hudHidden) return;
            if (RECORDER.isRecording()) {
                if ((System.currentTimeMillis() / 500) % 2 == 0) {
                    ctx.drawTextWithShadow(c.textRenderer, "[REC] " + mmss(RECORDER.seconds()), 6, 6, 0xFFFF5555);
                }
            } else if (PLAYBACK.isActive()) {
                String s = "[OYNATMA] " + (PLAYBACK.isPaused() ? "duraklatıldı  " : fmtSpeed(speed) + "x  ")
                        + mmss(PLAYBACK.currentSecond()) + " / " + mmss(PLAYBACK.totalSecond());
                ctx.drawTextWithShadow(c.textRenderer, s, 6, 6, 0xFF55FFFF);
            }
        });

        LOG.info("Replay Modu (TR) yüklendi. Menü: X tuşu");
    }

    // ---------- Yardımcılar ----------

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("replays_tr");
    }

    public static Path exportDir() {
        return dir().resolve("exports");
    }

    /** Sohbete mesaj yazar (hangi thread'den çağrılırsa çağrılsın ana thread'e taşır). */
    public static void say(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        c.execute(() -> {
            if (c.inGameHud != null) {
                c.inGameHud.getChatHud().addMessage(Text.literal("§6[Replay] §f" + msg));
            }
        });
    }

    public static String mmss(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private static String fmtSpeed(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ---------- Ayar dosyası: config/replaytr.properties ----------

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("replaytr.properties");
    }

    private static void loadConfig() {
        Path f = configFile();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Properties p = new Properties();
            p.load(r);
            double s = Double.parseDouble(p.getProperty("speed", "1.0"));
            speed = Math.max(0.1, Math.min(8.0, s));
        } catch (Exception e) {
            LOG.warn("Ayar dosyası okunamadı, varsayılan kullanılıyor", e);
        }
    }

    public static void saveConfigAsync() {
        double s = speed;
        IO.execute(() -> {
            try (Writer w = Files.newBufferedWriter(configFile())) {
                Properties p = new Properties();
                p.setProperty("speed", String.valueOf(s));
                p.store(w, "Replay Modu (TR) ayarlari");
            } catch (Exception e) {
                LOG.warn("Ayar kaydedilemedi", e);
            }
        });
    }
          }
