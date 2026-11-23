package cn.mcfhm.webbag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品贴图代理：服务端去 CDN 取图，缓存到本地再发给浏览器。
 * 浏览器不直连外部 CDN，网络差或 CDN 被墙时照样有图标。
 */
final class Textures {

    /** jar 内自带贴图对应的版本。 */
    private static final String BUNDLE_VERSION = "26.3";

    private final WebBagPlugin plugin;
    private final Path cacheDir;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final java.util.Map<String, Long> missAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MISSING_TTL_MS = 10 * 60_000L;
    private final java.util.Map<String, byte[]> bundled = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> uriCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object bundleLock = new Object();
    private volatile boolean bundleLoaded;

    private static volatile Textures instance;

    /** 背包 JSON 里直接塞图标，省掉一次图片请求。 */
    static String icon(String key) {
        Textures t = instance;
        return t == null ? null : t.dataUri(key);
    }

    private String dataUri(String key) {
        if (key == null || !validKey(key)) {
            return null;
        }
        String cached = uriCache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] png = null;
        for (String relative : candidates(key)) {
            png = fromBundle(relative);
            if (png != null) {
                break;
            }
        }
        if (png == null) {
            // 不缓存"没取到"，交给 missAt 的 TTL 决定什么时候重试
            return null;
        }
        String uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        uriCache.put(key, uri);
        return uri;
    }

    /** 自检：jar 内贴图是否加载、某个物品能不能取到图。 */
    static String diag(String key) {
        return diag(key, false);
    }

    /** full=true 时把全部缺失的 Material 列出来。 */
    static String diag(String key, boolean full) {
        Textures t = instance;
        if (t == null) {
            return "{\"bundle\":0,\"loaded\":false}";
        }
        t.loadBundle();
        String uri = t.dataUri(key == null || key.isBlank() ? "diamond_sword" : key);
        return "{\"bundle\":" + t.bundled.size()
                + ",\"sampleKey\":\"" + (key == null || key.isBlank() ? "diamond_sword" : key) + "\""
                + ",\"sampleBytes\":" + (uri == null ? 0 : uri.length())
                + ",\"coverage\":" + coverage(full ? 0 : 15)
                + "}";
    }

    /** 全量覆盖统计：所有可成图的 Material 里有多少能取到贴图，缺哪些。 */
    static String coverage() {
        return coverage(15);
    }

    /** sample 为 0 时列出全部缺失项，方便排查。 */
    static String coverage(int sample) {
        Textures t = instance;
        if (t == null) {
            return "{\"materials\":0,\"withIcon\":0,\"missing\":0,\"missingSample\":[]}";
        }
        t.loadBundle();
        int total = 0;
        int ok = 0;
        int missing = 0;
        StringBuilder sampleOut = new StringBuilder();
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (material.isAir() || material.isLegacy()) {
                continue;
            }
            String key = material.getKey().getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            total++;
            if (t.has(key)) {
                ok++;
                continue;
            }
            missing++;
            if (sample <= 0 || missing <= sample) {
                if (sampleOut.length() > 0) {
                    sampleOut.append(',');
                }
                sampleOut.append('"').append(key).append('"');
            }
        }
        return "{\"materials\":" + total + ",\"withIcon\":" + ok + ",\"missing\":" + missing
                + ",\"missingSample\":[" + sampleOut + "]}";
    }

    /** 只看 jar 包里有没有这张图，不生成 data URI（自检用，避免缓存一堆 base64）。 */
    private boolean has(String key) {
        if (!validKey(key)) {
            return false;
        }
        for (String relative : candidates(key)) {
            if (fromBundle(relative) != null) {
                return true;
            }
        }
        return false;
    }

    Textures(WebBagPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        this.cacheDir = plugin.getDataFolder().toPath().resolve("cache").resolve("tex");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create texture cache: " + e.getMessage());
        }
    }

    /** 合法的物品名，只允许小写字母数字下划线点横线，挡掉路径穿越。 */
    static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** 取贴图字节，取不到返回 null。 */
    byte[] get(String key) {
        Path cached = cacheDir.resolve(key + ".png");
        try {
            if (Files.isRegularFile(cached) && Files.size(cached) > 8) {
                return Files.readAllBytes(cached);
            }
        } catch (IOException ignored) {
            // 读不到就当没缓存
        }
        if (isMissing(key)) {
            return null;
        }

        for (String relative : candidates(key)) {
            byte[] png = fromBundle(relative);
            if (png != null) {
                save(cached, png);
                return png;
            }
        }
        // jar 里带的就是当前版本的全套贴图，命中不了再多走外网也没用
        if (BUNDLE_VERSION.equals(plugin.settings().assetsVersion())) {
            markMissing(key);
            return null;
        }
        for (String relative : candidates(key)) {
            byte[] png = fetch(relative);
            if (png != null) {
                save(cached, png);
                return png;
            }
        }
        markMissing(key);
        return null;
    }

    private boolean isMissing(String key) {
        Long at = missAt.get(key);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > MISSING_TTL_MS) {
            missAt.remove(key);
            return false;
        }
        return true;
    }

    private void markMissing(String key) {
        long now = System.currentTimeMillis();
        if (missAt.size() > 20_000) {
            missAt.entrySet().removeIf(entry -> now - entry.getValue() > MISSING_TTL_MS);
        }
        missAt.put(key, now);
    }

    /** jar 里带的原版贴图，服务器不联网也能出图标。 */
    private byte[] fromBundle(String relative) {
        loadBundle();
        return bundled.get(relative);
    }

    /** 开服预热：把 zip 解压放到异步线程，避免第一次打开网页时卡主线程。 */
    void warmup() {
        loadBundle();
    }

    private void loadBundle() {
        if (bundleLoaded) {
            return;
        }
        // 双检：首屏会并发请求几十张图，必须等第一个线程真正读完再放行，
        // 否则后到的线程拿到半空 map，会把这一批图标写进负缓存。
        synchronized (bundleLock) {
            if (bundleLoaded) {
                return;
            }
            try (java.io.InputStream in = Textures.class.getResourceAsStream("/textures.zip")) {
                if (in == null) {
                    plugin.getLogger().warning("Bundled textures.zip is missing, icons need network.");
                    bundleLoaded = true;
                    return;
                }
                try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        byte[] data = zip.readAllBytes();
                        if (isPng(data)) {
                            bundled.put(entry.getName().replace('\\', '/'), data);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not load bundled textures: " + e.getMessage());
            }
            bundleLoaded = true;
        }
    }

    /** 同一物品在新版本里贴图文件名变了的情况，直接给死路径。 */
    private static final java.util.Map<String, String> ALIASES = java.util.Map.of(
            "chest", "entity/chest/normal.png",
            "trapped_chest", "entity/chest/trapped.png",
            "ender_chest", "entity/chest/ender.png",
            "shield", "entity/shield/base.png",
            "mushroom_block", "block/red_mushroom_block.png",
            "grass_path", "block/dirt_path.png"
    );

    /** 同一个物品在不同版本里贴图文件名不一样，按可能性依次试。 */
    private java.util.List<String> candidates(String key) {
        java.util.List<String> list = new java.util.ArrayList<>();
        String alias = ALIASES.get(key);
        if (alias != null) {
            list.add(alias);
        }
        if (key.endsWith("_banner")) {
            list.add("entity/banner/base.png");
        }
        if (key.endsWith("potion")) {
            list.add("item/potion.png");
        }
        if (key.startsWith("enchanted_")) {
            list.add("item/" + key.substring("enchanted_".length()) + ".png");
        }
        list.add("item/" + key + ".png");
        list.add("block/" + key + ".png");
        list.add("block/" + key + "_side.png");
        list.add("block/" + key + "_top.png");
        list.add("block/" + key + "_front.png");
        list.add("block/" + key + "_front_on.png");
        list.add("block/" + key + "_on.png");
        list.add("block/" + key + "_bottom.png");
        list.add("item/" + key + "_front.png");
        list.add("item/" + key + "_standby.png");
        list.add("item/" + key + "_00.png");
        if (key.endsWith("_wood")) {
            String alt = key.substring(0, key.length() - 5) + "_log";
            list.add("block/" + alt + ".png");
            list.add("block/" + alt + "_side.png");
        }
        if (key.endsWith("_hyphae")) {
            list.add("block/" + key.substring(0, key.length() - 6) + "_stem.png");
        }
        if (key.endsWith("_sign")) {
            list.add("item/" + key.replace("_sign", "") + "_sign.png");
        }
        // 台阶、楼梯、栅栏、墙这些在原版里是立体模型，没有平面贴图，
        // 退一步用主方块（木板 / 羊毛 / 混凝土…）的贴图，总比空白好。
        String base = strippedBase(key);
        if (base != null) {
            list.add("item/" + base + ".png");
            list.add("block/" + base + ".png");
            list.add("block/" + base + "_planks.png");
            list.add("block/" + base + "_wool.png");
            list.add("block/" + base + "s.png");
            list.add("block/" + base + "_torch.png");
        }
        if (key.contains("_wall_")) {
            list.add("block/" + key.replace("_wall_", "_") + ".png");
        }
        return list;
    }

    private static final String[][] SUFFIX_BASE = {
            {"_wall_hanging_sign", "_hanging_sign"},
            {"_wall_sign", "_sign"},
            {"_hanging_sign", ""},
            {"_sign", ""},
            {"_slab", ""},
            {"_stairs", ""},
            {"_wall", ""},
            {"_fence_gate", ""},
            {"_fence", ""},
            {"_button", ""},
            {"_pressure_plate", ""},
            {"_carpet", "_wool"},
            {"_bed", "_wool"},
            {"_door", ""},
            {"_trapdoor", ""}
    };

    /** 把 acacia_slab 之类的名字还原成主方块名 acacia。 */
    private static String strippedBase(String key) {
        for (String[] rule : SUFFIX_BASE) {
            if (key.endsWith(rule[0])) {
                return key.substring(0, key.length() - rule[0].length()) + rule[1];
            }
        }
        return null;
    }

    /** 依次尝试的图源，第一个是配置里的，后面是备用镜像。 */
    private java.util.List<String> mirrors() {
        String version = plugin.settings().assetsVersion();
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(plugin.settings().assetsCdn());
        String jsdelivr = "https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@" + version + "/assets/minecraft";
        String raw = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/" + version + "/assets/minecraft";
        if (!list.contains(jsdelivr)) {
            list.add(jsdelivr);
        }
        if (!list.contains(raw)) {
            list.add(raw);
        }
        return list;
    }

    private void save(Path file, byte[] png) {
        try {
            Files.write(file, png);
        } catch (IOException ignored) {
            // 缓存写不进去也能照常用
        }
    }

    private byte[] fetch(String relative) {
        String path = "/textures/" + relative;
        for (String base : mirrors()) {
            byte[] png = fetchUrl(base + path);
            if (png != null) {
                return png;
            }
        }
        return null;
    }

    private byte[] fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WebBag/1.4")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && isPng(response.body())) {
                return response.body();
            }
        } catch (Exception ignored) {
            // 单个候选失败就试下一个
        }
        return null;
    }

    private static boolean isPng(byte[] data) {
        return data != null && data.length > 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }
}
