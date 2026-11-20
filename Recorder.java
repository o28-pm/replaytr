package com.replaytr;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ana thread sadece küçük bir nesne üretip kuyruğa atar (mikrosaniyeler).
 * Diske yazma işi tamamen "ReplayTR-Writer" thread'inde yapılır.
 */
public final class Recorder {
    private static final class Session {
        final BlockingQueue<ReplayIO.Entry> queue = new LinkedBlockingQueue<>(200_000);
        final Path file;
        volatile boolean open = true;

        Session(Path file) {
            this.file = file;
        }
    }

    private volatile Session session;
    private volatile int frameCount;
    private Item lastItem;
    private String lastItemId = "minecraft:air";

    public boolean isRecording() {
        return session != null;
    }

    public int seconds() {
        return frameCount / 20;
    }

    public boolean isWriting(Path p) {
        Session s = session;
        return s != null && s.file.equals(p);
    }

    public void toggle() {
        if (isRecording()) stop();
        else start();
    }

    public void start() {
        if (session != null) return;
        try {
            Files.createDirectories(ReplayModClient.dir());
        } catch (IOException e) {
            ReplayModClient.say("§cKlasör oluşturulamadı: " + e.getMessage());
            return;
        }
        String name = "kayit_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Session s = new Session(ReplayModClient.dir().resolve(name + ReplayIO.EXT));
        frameCount = 0;
        session = s;
        Thread t = new Thread(() -> writerLoop(s), "ReplayTR-Writer");
        t.setDaemon(true);
        t.start();
        ReplayModClient.say("§aKayıt başladı: §f" + name);
    }

    public void stop() {
        Session s = session;
        if (s == null) return;
        session = null;
        s.open = false; // yazıcı thread kuyruğu boşaltıp dosyayı kapatır
        ReplayModClient.say("§eKayıt durduruldu (" + ReplayModClient.mmss(frameCount / 20) + ").");
    }

    /** Her client tick sonunda ana thread'den çağrılır. */
    public void tick(MinecraftClient c) {
        Session s = session;
        if (s == null || c.player == null || c.world == null || c.isPaused()) return;
        ClientPlayerEntity p = c.player;

        Item item = p.getMainHandStack().getItem();
        if (item != lastItem) {
            lastItem = item;
            lastItemId = Registries.ITEM.getId(item).toString();
        }
        byte flags = (byte) ((p.isSneaking() ? 1 : 0) | (p.isSprinting() ? 2 : 0));

        // offer(): kuyruk doluysa bloklamaz, kareyi atar. Ana thread asla beklemez.
        s.queue.offer(new ReplayIO.Frame(p.getX(), p.getY(), p.getZ(),
                p.getYaw(), p.getPitch(), p.getHeadYaw(), flags, lastItemId));
        frameCount++;
    }

    public void onMessage(Text message) {
        Session s = session;
        if (s == null) return;
        String text = message.getString();
        if (text.length() > 2000) text = text.substring(0, 2000);
        s.queue.offer(new ReplayIO.Chat(frameCount, text));
    }

    private void writerLoop(Session s) {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(s.file), 1 << 16))) {
            ReplayIO.writeHeader(out, "1.21.4", System.currentTimeMillis());
            long lastFlush = System.nanoTime();
            while (s.open || !s.queue.isEmpty()) {
                ReplayIO.Entry e = s.queue.poll(250, TimeUnit.MILLISECONDS);
                if (e != null) ReplayIO.writeEntry(out, e);
                long now = System.nanoTime();
                if (e == null || now - lastFlush > 1_000_000_000L) {
                    out.flush(); // en fazla ~1 sn'lik veri çökmede kaybolur
                    lastFlush = now;
                }
            }
            out.writeByte(0); // bitiş işareti
        } catch (IOException | InterruptedException ex) {
            ReplayModClient.LOG.error("Kayıt yazma hatası", ex);
            s.open = false;
            if (session == s) session = null;
            ReplayModClient.say("§cKayıt yazılamadı: " + ex.getMessage());
        }
    }
    }
