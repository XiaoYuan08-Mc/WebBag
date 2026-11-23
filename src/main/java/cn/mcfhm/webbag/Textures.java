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

    private static final String[] DIRS = {"item", "block"};

    private final WebBagPlugin plugin;
    private final Path cacheDir;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    Textures(WebBagPlugin plugin) {
        this.plugin = plugin;
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
        if (missing.contains(key)) {
            return null;
        }

        for (String relative : candidates(key)) {
            byte[] png = fetch(relative);
            if (png != null) {
                save(cached, png);
                return png;
            }
        }
        if (missing.size() < 20_000) {
            missing.add(key);
        }
        return null;
    }

    /** 同一物品在新版本里贴图文件名变了的情况，直接给死路径。 */
    private static final java.util.Map<String, String> ALIASES = java.util.Map.of(
            "chest", "entity/chest/normal.png",
            "trapped_chest", "entity/chest/trapped.png",
            "ender_chest", "entity/chest/ender.png",
            "shield", "entity/shield/base.png"
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
        list.add("item/" + key + ".png");
        list.add("block/" + key + ".png");
        list.add("block/" + key + "_side.png");
        list.add("block/" + key + "_top.png");
        list.add("item/" + key + "_front.png");
        list.add("item/" + key + "_00.png");
        if (key.endsWith("_wood")) {
            String alt = key.substring(0, key.length() - 5) + "_log";
            list.add("block/" + alt + ".png");
            list.add("block/" + alt + "_side.png");
        }
        if (key.endsWith("_sign")) {
            list.add("item/" + key.replace("_sign", "") + "_sign.png");
        }
        return list;
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
