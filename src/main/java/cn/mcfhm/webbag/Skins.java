package cn.mcfhm.webbag;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 取玩家正在使用的皮肤，裁成头像供页面使用。
 */
public final class Skins {

    private final WebBagPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public Skins(WebBagPlugin plugin) {
        this.plugin = plugin;
    }

    /** 返回 64×64 的头像 PNG；玩家离线或没有皮肤贴图时返回 null。 */
    public byte[] head(UUID uuid) {
        String url = skinUrl(uuid);
        if (url == null || url.isBlank()) {
            return null;
        }
        byte[] cached = cache.get(url);
        if (cached != null) {
            return cached;
        }
        byte[] png = fetchHead(url);
        if (png != null) {
            if (cache.size() > 800) {
                cache.clear();
            }
            cache.put(url, png);
        }
        return png;
    }

    private String skinUrl(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    future.complete(null);
                    return;
                }
                PlayerTextures textures = player.getPlayerProfile().getTextures();
                if (textures == null || textures.getSkin() == null) {
                    future.complete(null);
                } else {
                    future.complete(textures.getSkin().toString());
                }
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] fetchHead(String url) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("User-Agent", "WebBag")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                return null;
            }
            return HeadCutter.crop(response.body());
        } catch (Exception e) {
            return null;
        }
    }
}
