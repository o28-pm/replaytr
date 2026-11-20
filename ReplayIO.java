package com.replaytr;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Kayıt dosyası formatı (.rtr): okuma, yazma, listeleme, dışa aktarma. */
public final class ReplayIO {
    public static final int MAGIC = 0x52545231; // "RTR1"
    public static final int VERSION = 1;
    public static final String EXT = ".rtr";

    private ReplayIO() {}

    public interface Entry {}

    /** Her oyun tick'inde (saniyede 20) bir kare. */
    public record Frame(double x, double y, double z, float yaw, float pitch, float headYaw,
                        byte flags, String item) implements Entry {}

    /** Sohbet / sistem mesajı olayı. frameIndex: mesajın geldiği kare. */
    public record Chat(int frameIndex, String text) implements Entry {}

    public record Info(Path path, String name, long sizeBytes, long modified) {}

    public static final class Data {
        public final List<Frame> frames = new ArrayList<>();
        public final List<Chat> chats = new ArrayList<>();
        public long startMillis;
    }

    // ---------- YAZMA (yazıcı thread'den çağrılır) ----------

    public static void writeHeader(DataOutputStream out, String mcVersion, long startMillis) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(mcVersion);
        out.writeLong(startMillis);
    }

    public static void writeEntry(DataOutputStream out, Entry e) throws IOException {
        if (e instanceof Frame f) {
            out.writeByte(1);
            out.writeDouble(f.x());
            out.writeDouble(f.y());
            out.writeDouble(f.z());
            out.writeFloat(f.yaw());
            out.writeFloat(f.pitch());
            out.writeFloat(f.headYaw());
            out.writeByte(f.flags());
            out.writeUTF(f.item());
        } else if (e instanceof Chat c) {
            out.writeByte(2);
            out.writeInt(c.frameIndex());
            out.writeUTF(c.text());
        }
    }

    // ---------- OKUMA ----------

    public static Data read(Path file) throws IOException {
        Data d = new Data();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("Geçersiz kayıt dosyası");
            in.readInt();    // sürüm
            in.readUTF();    // oyun sürümü
            d.startMillis = in.readLong();
            while (true) {
                int type = in.read();
                if (type <= 0) break; // 0 = bitiş, -1 = dosya sonu
                if (type == 1) {
                    d.frames.add(new Frame(in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readFloat(), in.readFloat(), in.readFloat(), in.readByte(), in.readUTF()));
                } else if (type == 2) {
                    d.chats.add(new Chat(in.readInt(), in.readUTF()));
                } else {
                    break;
                }
            }
        } catch (EOFException ignored) {
            // Oyun çökmüş / kapanmış olabilir: okunabilen kısmı kullan
        }
        return d;
    }

    // ---------- LİSTE ----------

    public static List<Info> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            List<Info> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !fn.endsWith(EXT)) continue;
                out.add(new Info(p, stripExt(fn), Files.size(p), Files.getLastModifiedTime(p).toMillis()));
            }
            out.sort(Comparator.comparingLong(Info::modified).reversed());
            return out;
        }
    }

    // ---------- DIŞA AKTARMA ----------

    /** .zip üretir: orijinal .rtr + okunabilir .csv + sohbet olayları .txt */
    public static Path export(Path file, Path exportDir) throws IOException {
        Files.createDirectories(exportDir);
        String base = stripExt(file.getFileName().toString());
        Path zip = exportDir.resolve(base + ".zip");
        Data d = read(file);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(base + EXT));
            Files.copy(file, z);
            z.closeEntry();

            z.putNextEntry(new ZipEntry(base + ".csv"));
            z.write(toCsv(d).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();

            z.putNextEntry(new ZipEntry(base + "_sohbet.txt"));
            z.write(toChatText(d).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return zip;
    }

    private static String toCsv(Data d) {
        StringBuilder sb = new StringBuilder("kare,saniye,x,y,z,yaw,pitch,kafa_yaw,egilme,kosu,esya\n");
        int i = 0;
        for (Frame f : d.frames) {
            sb.append(i).append(',')
              .append(String.format(Locale.ROOT, "%.2f", i / 20.0)).append(',')
              .append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.2f,%.2f,%.2f", f.x(), f.y(), f.z(), f.yaw(), f.pitch(), f.headYaw()))
              .append(',').append((f.flags() & 1) != 0 ? 1 : 0)
              .append(',').append((f.flags() & 2) != 0 ? 1 : 0)
              .append(',').append(f.item()).append('\n');
            i++;
        }
        return sb.toString();
    }

    private static String toChatText(Data d) {
        StringBuilder sb = new StringBuilder();
        for (Chat c : d.chats) {
            sb.append(String.format(Locale.ROOT, "[%.1f sn] ", c.frameIndex() / 20.0)).append(c.text()).append('\n');
        }
        return sb.toString();
    }

    private static String stripExt(String name) {
        return name.endsWith(EXT) ? name.substring(0, name.length() - EXT.length()) : name;
    }
    }
