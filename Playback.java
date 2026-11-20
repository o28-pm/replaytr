package com.replaytr;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Oynatma: kayıttaki konumda bir "hayalet oyuncu" oluşturur ve kamerayı ona bağlar.
 * Hız = tick başına ilerleyen kare sayısı (0.25x ... 4x), kareler arası interpolasyon yapılır.
 */
public final class Playback {
    private volatile boolean loading;
    private boolean active;
    private boolean paused;
    private ReplayIO.Data data;
    private double cursor;
    private int nextChat;
    private OtherClientPlayerEntity ghost;
    private Perspective oldPerspective;
    private String shownItem = "";

    public boolean isActive() { return active; }
    public boolean isLoading() { return loading; }
    public boolean isPaused() { return paused; }
    public void togglePause() { paused = !paused; }
    public int currentSecond() { return (int) (cursor / 20); }
    public int totalSecond() { return data == null ? 0 : data.frames.size() / 20; }

    public void start(MinecraftClient c, ReplayIO.Info info) {
        if (active || loading) return;
        if (ReplayModClient.RECORDER.isRecording()) {
            ReplayModClient.say("§cÖnce kaydı durdurun.");
            return;
        }
        loading = true;
        ReplayModClient.say("§7Kayıt yükleniyor: " + info.name());
        CompletableFuture.supplyAsync(() -> {
            try {
                return ReplayIO.read(info.path());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, ReplayModClient.IO).whenComplete((d, err) -> c.execute(() -> {
            loading = false;
            if (err != null || d == null || d.frames.size() < 2) {
                ReplayModClient.say("§cKayıt okunamadı veya çok kısa.");
                return;
            }
            begin(c, d);
        }));
    }

    private void begin(MinecraftClient c, ReplayIO.Data d) {
        if (c.world == null || c.player == null) return;
        data = d;
        cursor = 0;
        nextChat = 0;
        paused = false;
        shownItem = "";

        GameProfile profile = c.player.getGameProfile();          // kendi skinini kullan
        ghost = new OtherClientPlayerEntity(c.world, profile);
        ghost.setUuid(UUID.randomUUID());                         // aynı UUID dünyaya eklenemez
        ghost.setId(-20000);                                      // sunucu ID'leriyle çakışmasın
        c.world.addEntity(ghost);

        oldPerspective = c.options.getPerspective();
        c.options.setPerspective(Perspective.FIRST_PERSON);
        c.setCameraEntity(ghost);
        active = true;
        apply(c);
        ReplayModClient.say("§aOynatma başladı. §7(F5 ile kamera açısını değiştirebilirsin)");
    }

    /** START_CLIENT_TICK: gerçek oyuncunun girdilerini engelle (oynatma sırasında hareket etmesin). */
    public void preTick(MinecraftClient c) {
        if (!active) return;
        GameOptions o = c.options;
        block(o.forwardKey);
        block(o.backKey);
        block(o.leftKey);
        block(o.rightKey);
        block(o.jumpKey);
        block(o.sneakKey);
        block(o.sprintKey);
        block(o.attackKey);
        block(o.useKey);
        block(o.dropKey);
        block(o.swapHandsKey);
        block(o.pickItemKey);
    }

    private static void block(KeyBinding k) {
        k.setPressed(false);
        while (k.wasPressed()) { /* birikmiş tıklamaları tüket */ }
    }

    /** END_CLIENT_TICK */
    public void tick(MinecraftClient c) {
        if (!active) return;
        if (c.world == null || c.player == null || ghost == null || ghost.isRemoved()) {
            stop(c);
            return;
        }
        int last = data.frames.size() - 1;
        if (!paused) cursor += ReplayModClient.speed;
        if (cursor >= last) {
            cursor = last;
            apply(c);
            ReplayModClient.say("§eOynatma bitti.");
            stop(c);
            return;
        }
        apply(c);
    }

    private void apply(MinecraftClient c) {
        List<ReplayIO.Frame> frames = data.frames;
        int last = frames.size() - 1;
        int i = Math.min((int) cursor, last);
        double f = cursor - i;
        ReplayIO.Frame a = frames.get(i);
        ReplayIO.Frame b = frames.get(Math.min(i + 1, last));

        ghost.setPosition(lerp(a.x(), b.x(), f), lerp(a.y(), b.y(), f), lerp(a.z(), b.z(), f));
        float yaw = (float) lerp(a.yaw(), b.yaw(), f);
        ghost.setYaw(yaw);
        ghost.setPitch((float) lerp(a.pitch(), b.pitch(), f));
        ghost.setHeadYaw((float) lerp(a.headYaw(), b.headYaw(), f));
        ghost.setBodyYaw(yaw);
        ghost.setSneaking((a.flags() & 1) != 0);
        ghost.setSprinting((a.flags() & 2) != 0);

        if (!a.item().equals(shownItem)) {
            shownItem = a.item();
            ghost.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Registries.ITEM.get(Identifier.of(shownItem))));
        }

        List<ReplayIO.Chat> chats = data.chats;
        while (nextChat < chats.size() && chats.get(nextChat).frameIndex() <= i) {
            c.inGameHud.getChatHud().addMessage(Text.literal("§7[Kayıt] §r" + chats.get(nextChat).text()));
            nextChat++;
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public void stop(MinecraftClient c) {
        if (!active) return;
        active = false;
        if (c.world != null && ghost != null) {
            c.world.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
        }
        if (c.player != null) c.setCameraEntity(c.player);
        if (oldPerspective != null) c.options.setPerspective(oldPerspective);
        ghost = null;
        data = null;
    }

    /** Bağlantı kopunca çağrılır: dünya zaten yok, sadece durumu sıfırla. */
    public void reset(MinecraftClient c) {
        active = false;
        loading = false;
        ghost = null;
        data = null;
        if (oldPerspective != null) c.options.setPerspective(oldPerspective);
    }
}
