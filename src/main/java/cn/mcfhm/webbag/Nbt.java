package cn.mcfhm.webbag;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简 NBT 读取器，只用来读 playerdata 里离线玩家的背包。
 * 读到的结构：Compound -> Map&lt;String,Object&gt;，List -> List&lt;Object&gt;。
 */
final class Nbt {

    private Nbt() {
    }

    /** 读一个 .dat 文件（通常是 gzip 压缩），返回根 Compound。 */
    static Map<String, Object> read(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.mark(4);
            int b0 = raw.read();
            int b1 = raw.read();
            raw.reset();
            InputStream in = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(raw) : raw;
            DataInputStream data = new DataInputStream(in);
            byte type = data.readByte();
            if (type != 10) {
                throw new IOException("根标签不是 Compound");
            }
            data.readUTF();
            return readCompound(data);
        } catch (EOFException e) {
            throw new IOException("playerdata 文件不完整", e);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                return map;
            }
            String name = readString(in);
            map.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> {
                return in.readByte();
            }
            case 2 -> {
                return in.readShort();
            }
            case 3 -> {
                return in.readInt();
            }
            case 4 -> {
                return in.readLong();
            }
            case 5 -> {
                return in.readFloat();
            }
            case 6 -> {
                return in.readDouble();
            }
            case 7 -> {
                int len = in.readInt();
                if (len < 0 || len > 8 * 1024 * 1024) {
                    throw new IOException("异常的 ByteArray 长度：" + len);
                }
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return bytes;
            }
            case 8 -> {
                return readString(in);
            }
            case 9 -> {
                byte itemType = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(len, 4096)));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, itemType));
                }
                return list;
            }
            case 10 -> {
                return readCompound(in);
            }
            case 11 -> {
                int len = in.readInt();
                int[] arr = new int[Math.max(0, Math.min(len, 8 * 1024 * 1024))];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readInt();
                }
                return arr;
            }
            case 12 -> {
                int len = in.readInt();
                long[] arr = new long[Math.max(0, Math.min(len, 8 * 1024 * 1024))];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readLong();
                }
                return arr;
            }
            default -> throw new IOException("未知的 NBT 标签类型：" + type);
        }
    }

    /** NBT 字符串是 UTF-8 字节流，不能直接用 readUTF（会读坏 emoji）。 */
    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
