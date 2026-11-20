package com.replaytr;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** X tuşuyla açılan Türkçe menü. */
public final class ReplayScreen extends Screen {
    private static final double[] SPEEDS = {0.25, 0.5, 1.0, 2.0, 4.0};
    private static final int PER_PAGE = 3;

    private List<ReplayIO.Info> infos = List.of();
    private int page = 0;
    private int selected = -1;
    private boolean confirmDelete = false;
    private String status = "";
    private int top;
    private int labelY;

    public ReplayScreen() {
        super(Text.literal("Replay Menüsü"));
        reload();
    }

    private void reload() {
        try {
            infos = ReplayIO.list(ReplayModClient.dir());
        } catch (IOException e) {
            infos = List.of();
            status = "§cKlasör okunamadı";
        }
        if (selected >= infos.size()) selected = -1;
    }

    @Override
    public boolean shouldPause() {
        return false; // menü açıkken oyun durmasın, kayıt kesilmesin
    }

    @Override
    protected void init() {
        final int w = 200, h = 18, step = 20;
        int cx = width / 2;
        int x = cx - w / 2;
        top = Math.max(18, (height - 230) / 2 + 12);
        int y = top;

        boolean recording = ReplayModClient.RECORDER.isRecording();
        boolean playing = ReplayModClient.PLAYBACK.isActive();
        boolean loading = ReplayModClient.PLAYBACK.isLoading();

        // 1) Kayıt aç/kapa
        ButtonWidget rec = btn(recording ? "§cKayıt: AÇIK §7(durdur)" : "§aKayıt: KAPALI §7(başlat)", x, y, w, h, b -> {
            ReplayModClient.RECORDER.toggle();
            reload();
            refresh();
        });
        rec.active = !playing && !loading;
        y += step;

        // 2) Oynatma hızı
        btn("Oynatma hızı: §e" + fmt(ReplayModClient.speed) + "x", x, y, w, h, b -> {
            ReplayModClient.speed = nextSpeed(ReplayModClient.speed);
            ReplayModClient.saveConfigAsync();
            refresh();
        });
        y += step;

        // 3) Oynatma kontrolleri
        ButtonWidget pause = btn(ReplayModClient.PLAYBACK.isPaused() ? "Devam" : "Duraklat", x, y, 98, h, b -> {
            ReplayModClient.PLAYBACK.togglePause();
            refresh();
        });
        pause.active = playing;
        ButtonWidget stop = btn("Oynatmayı durdur", x + 102, y, 98, h, b -> {
            ReplayModClient.PLAYBACK.stop(client);
            refresh();
        });
        stop.active = playing;
        y += step;

        // 4) Liste
        labelY = y + 2;
        y += 12;
        int pages = Math.max(1, (infos.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = from + i;
            if (idx < infos.size()) {
                ReplayIO.Info in = infos.get(idx);
                String label = (idx == selected ? "§e> " : "§f") + in.name() + " §7(" + kb(in.sizeBytes()) + ")";
                btn(label, x, y, w, h, b -> {
                    selected = idx;
                    confirmDelete = false;
                    refresh();
                });
            } else {
                btn("§8-", x, y, w, h, b -> {}).active = false;
            }
            y += step;
        }

        // 5) Sayfa düğmeleri
        ButtonWidget prev = btn("< Önceki", x, y, 98, h, b -> {
            page--;
            refresh();
        });
        prev.active = page > 0;
        ButtonWidget next = btn("Sonraki >", x + 102, y, 98, h, b -> {
            page++;
            refresh();
        });
        next.active = page < pages - 1;
        y += step;

        // 6) Seçili kayıt işlemleri
        boolean hasSel = selected >= 0 && selected < infos.size();
        ButtonWidget play = btn("Oynat", x, y, 64, h, b -> {
            ReplayModClient.PLAYBACK.start(client, infos.get(selected));
            close();
        });
        play.active = hasSel && !recording && !playing && !loading;

        ButtonWidget export = btn("Dışa aktar", x + 68, y, 64, h, b -> doExport());
        export.active = hasSel;

        ButtonWidget del = btn(confirmDelete ? "§cEmin misin?" : "Sil", x + 136, y, 64, h, b -> doDelete());
        del.active = hasSel;
        y += step;

        // 7) Kapat
        btn("Kapat", x, y, w, h, b -> close());
    }

    private void doExport() {
        ReplayIO.Info in = infos.get(selected);
        if (ReplayModClient.RECORDER.isWriting(in.path())) {
            status = "§cBu kayıt sürüyor, önce durdur.";
            refresh();
            return;
        }
        status = "§eDışa aktarılıyor...";
        refresh();
        MinecraftClient mc = this.client;
        ReplayModClient.IO.execute(() -> {
            try {
                Path z = ReplayIO.export(in.path(), ReplayModClient.exportDir());
                String shown = "replays_tr/exports/" + z.getFileName();
                mc.execute(() -> status = "§aDışa aktarıldı: " + shown);
            } catch (IOException e) {
                mc.execute(() -> status = "§cHata: " + e.getMessage());
            }
        });
    }

    private void doDelete() {
        ReplayIO.Info in = infos.get(selected);
        if (ReplayModClient.RECORDER.isWriting(in.path())) {
            status = "§cBu kayıt sürüyor, önce durdur.";
            refresh();
            return;
        }
        if (!confirmDelete) {
            confirmDelete = true;
            refresh();
            return;
        }
        confirmDelete = false;
        MinecraftClient mc = this.client;
        ReplayModClient.IO.execute(() -> {
            try {
                Files.deleteIfExists(in.path());
                mc.execute(() -> {
                    status = "§eSilindi: " + in.name();
                    selected = -1;
                    reload();
                    if (mc.currentScreen == this) refresh();
                });
            } catch (IOException e) {
                mc.execute(() -> status = "§cSilinemedi: " + e.getMessage());
            }
        });
    }

    private void refresh() {
        this.clearAndInit();
    }

    private ButtonWidget btn(String label, int x, int y, int w, int h, ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, h).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ReplayModClient.MENU_KEY.matchesKey(keyCode, scanCode)) {
            close(); // X'e tekrar basınca kapanır
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§lKayıt / Replay Menüsü"), cx, top - 12, 0xFFFFFFFF);

        int pages = Math.max(1, (infos.size() + PER_PAGE - 1) / PER_PAGE);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Kayıtlar (sayfa " + (page + 1) + "/" + pages + ")"), cx, labelY, 0xFFAAAAAA);

        String info = "";
        if (ReplayModClient.RECORDER.isRecording()) {
            info = "§cKayıt sürüyor: " + ReplayModClient.mmss(ReplayModClient.RECORDER.seconds());
        } else if (ReplayModClient.PLAYBACK.isActive()) {
            info = "§bOynatılıyor: " + ReplayModClient.mmss(ReplayModClient.PLAYBACK.currentSecond())
                    + " / " + ReplayModClient.mmss(ReplayModClient.PLAYBACK.totalSecond());
        } else if (ReplayModClient.PLAYBACK.isLoading()) {
            info = "§7Yükleniyor...";
        }
        if (!info.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(info), cx, height - 24, 0xFFFFFFFF);
        }
        if (!status.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, height - 12, 0xFFFFFF55);
        }
    }

    private static double nextSpeed(double cur) {
        for (double s : SPEEDS) {
            if (s > cur + 1e-9) return s;
        }
        return SPEEDS[0];
    }

    private static String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String kb(long bytes) {
        return (bytes / 1024) + " KB";
    }
    }
