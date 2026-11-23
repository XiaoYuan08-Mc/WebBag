package cn.mcfhm.webbag;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 读服务器上的 playerdata 文件，给离线玩家出背包。
 * 只在 HTTP 线程里跑，不碰主线程；文件读取全部走 NBT 解析，不需要服务端 API。
 */
final class OfflineData {

    private static final String[] MODE = {"SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"};

    private OfflineData() {
    }

    /**
     * 玩家存档目录。26.3 的世界目录是 world/dimensions/minecraft/overworld，玩家存档却在 world/players，
     * 所以从世界目录往上找几层；旧版则直接是 world/playerdata。
     */
    static List<Path> playerdataDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            Path base = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            for (int up = 0; up <= 4 && base != null; up++) {
                for (String child : new String[]{"players", "playerdata"}) {
                    Path dir = base.resolve(child);
                    if (Files.isDirectory(dir)) {
                        dirs.add(dir);
                    }
                }
                base = base.getParent();
            }
        }
        return new ArrayList<>(dirs);
    }

    /** 查找某个 UUID 的 playerdata 文件，找不到返回 null。 */
    static Path find(List<Path> dirs, UUID uuid) {
        String name = uuid.toString() + ".dat";
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (Path dir : dirs) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified > newestTime) {
                    newestTime = modified;
                    newest = file;
                }
            } catch (IOException ignored) {
                // 忽略读不到时间戳的文件
            }
        }
        return newest;
    }

    /** 目录里所有玩家存档文件（<uuid>.dat），按 UUID 解析。 */
    static List<Path> playerFiles(List<Path> dirs) {
        List<Path> out = new ArrayList<>();
        for (Path dir : dirs) {
            try (var stream = Files.newDirectoryStream(dir, "*.dat")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    if (name.length() != 40 || !name.endsWith(".dat")) {
                        continue;
                    }
                    out.add(file);
                }
            } catch (IOException ignored) {
                // 目录读不到就跳过
            }
        }
        return out;
    }

    /** UUID 从存档文件名取。 */
    static UUID uuidOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** usercache.json 里的 uuid -> 名字，用于离线玩家的显示名。 */
    static Map<String, String> userCache() {
        Map<String, String> out = new HashMap<>();
        Path file = Bukkit.getWorldContainer().toPath().resolve("usercache.json");
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            int idx = 0;
            while ((idx = text.indexOf("\"uuid\"", idx)) >= 0) {
                int uStart = text.indexOf('"', text.indexOf(':', idx) + 1);
                int uEnd = uStart < 0 ? -1 : text.indexOf('"', uStart + 1);
                if (uEnd < 0) {
                    break;
                }
                String uuid = text.substring(uStart + 1, uEnd);
                String name = nameBefore(text, idx);
                if (!uuid.isBlank() && name != null) {
                    out.put(uuid, name);
                }
                idx = uEnd + 1;
            }
        } catch (IOException ignored) {
            // 读不到就当没有
        }
        return out;
    }

    private static String nameBefore(String text, int before) {
        int idx = text.lastIndexOf("\"name\"", before);
        if (idx < 0) {
            return null;
        }
        int start = text.indexOf('"', text.indexOf(':', idx) + 1);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        String name = text.substring(start + 1, end);
        return name.isBlank() ? null : name;
    }

    static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 生成与在线快照同结构的 JSON。name 用于显示（playerdata 里不存名字）。
     */
    static String snapshot(String name, UUID uuid, Path file, boolean ender) throws IOException {
        Map<String, Object> root = Nbt.read(file);

        Json json = new Json(16 * 1024);
        json.beginObject();
        json.name("ok").value(true);
        json.name("type").value(ender ? "ender" : "inv");
        json.name("offline").value(true);
        json.name("time").value(System.currentTimeMillis());
        json.name("lastSeen").value(lastModified(file));

        json.name("player").beginObject();
        json.name("name").value(name);
        json.name("uuid").value(uuid.toString());
        json.name("world").value(dimension(root.get("Dimension")));
        json.name("mode").value(gameMode(root.get("playerGameType")));
        json.name("health").value(num(root.get("Health"), 20.0));
        json.name("maxHealth").value(20.0);
        json.name("food").value((int) num(root.get("foodLevel"), 20.0));
        json.name("level").value((int) num(root.get("XpLevel"), 0.0));
        json.name("armor").value(0.0);
        double[] pos = position(root.get("Pos"));
        json.name("x").value((int) pos[0]);
        json.name("y").value((int) pos[1]);
        json.name("z").value((int) pos[2]);
        json.endObject();

        json.name("slots").beginArray();
        Object list = root.get(ender ? "EnderItems" : "Inventory");
        if (list instanceof List<?> items) {
            for (Object raw : items) {
                if (raw instanceof Map<?, ?> item) {
                    int slot = (int) num(item.get("Slot"), 0.0);
                    writeItem(json, item, slot);
                }
            }
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    // ------------------------------------------------------------------
    // 物品
    // ------------------------------------------------------------------

    private static void writeItem(Json json, Map<?, ?> item, int slot) {
        String id = str(item.get("id"));
        if (id == null || id.isBlank()) {
            return;
        }
        String key = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        Material material = Material.matchMaterial(key);
        if (material == null || material.isAir()) {
            return;
        }
        int amount = (int) num(item.get("count"), num(item.get("Count"), 1.0));
        if (amount <= 0) {
            return;
        }

        Object components = item.get("components");
        Map<?, ?> comp = components instanceof Map<?, ?> m ? m : Map.of();

        json.beginObject();
        json.name("slot").value(slot);
        json.name("key").value(key);
        json.name("amount").value(amount);
        json.name("max").value(Math.max(1, material.getMaxStackSize()));

        String name = text(comp.get("minecraft:custom_name"));
        if (name == null) {
            name = legacyName(item);
        }
        json.name("name").value(name);

        List<String> lore = lore(comp.get("minecraft:lore"));
        if (lore.isEmpty()) {
            lore = legacyLore(item);
        }
        if (!lore.isEmpty()) {
            json.name("lore").beginArray();
            for (String line : lore) {
                json.value(line);
            }
            json.endArray();
        }

        List<String[]> ench = enchants(comp);
        if (!ench.isEmpty()) {
            json.name("ench").beginArray();
            for (String[] e : ench) {
                json.beginObject();
                json.name("k").value(e[0]);
                json.name("l").value(Integer.parseInt(e[1]));
                json.endObject();
            }
            json.endArray();
        }

        int maxDura = (int) num(comp.get("minecraft:max_damage"), material.getMaxDurability());
        int damage = (int) num(comp.get("minecraft:damage"), 0.0);
        if (maxDura > 1 && damage > 0) {
            json.name("dura").value(damage);
            json.name("maxDura").value(maxDura);
        }
        if (comp.containsKey("minecraft:unbreakable")) {
            json.name("unbreakable").value(true);
        }
        if (comp.containsKey("minecraft:custom_model_data")) {
            json.name("cmd").value((int) num(comp.get("minecraft:custom_model_data"), 0.0));
        }
        json.endObject();
    }

    /** 附魔：返回 {名字, 等级}；兼容新格式 levels 与旧格式 Enchantments 列表。 */
    private static List<String[]> enchants(Map<?, ?> comp) {
        List<String[]> out = new ArrayList<>();
        addEnchants(out, comp.get("minecraft:enchantments"));
        if (out.isEmpty()) {
            addEnchants(out, comp.get("minecraft:stored_enchantments"));
        }
        if (out.isEmpty() && comp.get("Enchantments") instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> e) {
                    String key = enchantName(str(e.get("id")));
                    if (key != null) {
                        out.add(new String[]{key, String.valueOf((int) num(e.get("lvl"), 1.0))});
                    }
                }
            }
        }
        return out;
    }

    private static void addEnchants(List<String[]> out, Object node) {
        if (!(node instanceof Map<?, ?> en)) {
            return;
        }
        if (!(en.get("levels") instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = enchantName(String.valueOf(e.getKey()));
            if (key != null) {
                out.add(new String[]{key, String.valueOf((int) num(e.getValue(), 1.0))});
            }
        }
    }

    private static String enchantName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
    }

    /** 旧版 playerdata 把名字和介绍放在 display 里。 */
    private static String legacyName(Map<?, ?> item) {
        Object display = item.get("display");
        if (display instanceof Map<?, ?> d) {
            return text(d.get("Name"));
        }
        return null;
    }

    private static List<String> legacyLore(Map<?, ?> item) {
        Object display = item.get("display");
        if (display instanceof Map<?, ?> d && d.get("Lore") instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object raw : list) {
                String line = text(raw);
                if (line != null) {
                    out.add(line);
                }
            }
            return out;
        }
        return List.of();
    }

    private static List<String> lore(Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object raw : list) {
            String line = text(raw);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 文本与数值
    // ------------------------------------------------------------------

    /** 文本组件在 NBT 里可能是纯字符串，也可能是 {text:...} 或 JSON 字符串。 */
    private static String text(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{")) {
                String inner = firstJsonText(trimmed);
                return inner == null || inner.isBlank() ? null : inner;
            }
            return trimmed.isBlank() ? null : trimmed;
        }
        if (node instanceof Map<?, ?> map) {
            Object plain = map.get("text");
            if (plain instanceof String t && !t.isBlank()) {
                return t;
            }
            Object extra = map.get("extra");
            if (extra instanceof List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object raw : list) {
                    String part = text(raw);
                    if (part != null) {
                        sb.append(part);
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            Object translate = map.get("translate");
            if (translate instanceof String t) {
                return t;
            }
        }
        return null;
    }

    private static String firstJsonText(String json) {
        int idx = json.indexOf("\"text\"");
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String dimension(Object node) {
        String raw = node == null ? "" : String.valueOf(node);
        if (raw.startsWith("minecraft:")) {
            raw = raw.substring("minecraft:".length());
        }
        return raw.isBlank() ? "-" : raw;
    }

    private static String gameMode(Object node) {
        int idx = (int) num(node, 0.0);
        return idx >= 0 && idx < MODE.length ? MODE[idx] : "SURVIVAL";
    }

    private static double[] position(Object node) {
        if (node instanceof List<?> list && list.size() >= 3) {
            return new double[]{num(list.get(0), 0.0), num(list.get(1), 0.0), num(list.get(2), 0.0)};
        }
        return new double[]{0, 0, 0};
    }

    private static String str(Object node) {
        return node instanceof String s ? s : null;
    }

    private static double num(Object node, double fallback) {
        if (node instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }
}
