package cn.mcfhm.webbag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 网页背包：管理员通过浏览器查看全部在线玩家的背包。
 */
public final class WebBagPlugin extends JavaPlugin {

    public record Settings(
            int port,
            String bind,
            String publicUrl,
            boolean allowEnderChest,
            String assetsCdn,
            String assetsVersion,
            int refreshSeconds,
            boolean liveUpdate,
            int liveIntervalMs,
            int rateLimitPerMinute
    ) {
    }

    private volatile Settings settings;
    private AccessKey accessKey;
    private HttpApiServer http;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = readSettings();
        this.accessKey = new AccessKey(ensureAccessKey());
        this.http = new HttpApiServer(this, accessKey);

        if (http.start()) {
            getLogger().info("Web inventory dashboard listening on " + settings.bind() + ":" + settings.port());
            getLogger().info("Run /webbag as an administrator to get the access link.");
            // 贴图包在异步线程预热：第一次打开网页时不至于让主线程解压 zip
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    http.warmupTextures();
                } catch (Throwable t) {
                    getLogger().warning("预热贴图包失败: " + t.getMessage());
                }
            });
        } else {
            getLogger().severe("Failed to start HTTP service. Check port " + settings.port() + " and bind address.");
        }
    }

    @Override
    public void onDisable() {
        if (http != null) {
            http.stop();
        }
    }

    public Settings settings() {
        return settings;
    }

    public AccessKey accessKey() {
        return accessKey;
    }

    /** 读取配置；端口默认 5001。 */
    private Settings readSettings() {
        var c = getConfig();

        int port = c.getInt("port", 5001);
        if (port < 1 || port > 65535) {
            getLogger().warning("Invalid port (" + port + "), falling back to 5001.");
            port = 5001;
        }

        String bind = c.getString("bind", "0.0.0.0");
        if (bind == null || bind.isBlank()) {
            bind = "0.0.0.0";
        }

        String publicUrl = c.getString("public-url", "");
        publicUrl = publicUrl == null ? "" : publicUrl.trim();
        while (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }

        String cdn = c.getString("assets-cdn",
                "https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@{version}/assets/minecraft");
        String version = c.getString("assets-version", "26.3");
        if (cdn == null || cdn.isBlank()) {
            cdn = "https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@{version}/assets/minecraft";
        }
        cdn = cdn.replace("{version}", version);
        while (cdn.endsWith("/")) {
            cdn = cdn.substring(0, cdn.length() - 1);
        }

        return new Settings(
                port,
                bind,
                publicUrl,
                c.getBoolean("allow-ender-chest", true),
                cdn,
                version,
                Math.max(0, c.getInt("refresh-seconds", 10)),
                c.getBoolean("live-update", true),
                Math.max(500, c.getInt("live-interval-ms", 1000)),
                Math.max(10, c.getInt("rate-limit-per-minute", 180))
        );
    }

    private String ensureAccessKey() {
        String configured = getConfig().getString("access-key", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String generated = new AccessKey("").generate();
        getConfig().set("access-key", generated);
        saveConfig();
        getLogger().info("Generated a new access key and saved it to config.yml.");
        return generated;
    }

    /** 在线玩家列表（主线程执行）。 */
    public CompletableFuture<String> capturePlayersAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

                Json json = new Json(4096);
                json.beginObject();
                json.name("ok").value(true);
                json.name("time").value(System.currentTimeMillis());
                json.name("count").value(online.size());
                json.name("players").beginArray();
                for (Player p : online) {
                    json.beginObject();
                    json.name("name").value(p.getName());
                    json.name("uuid").value(p.getUniqueId().toString());
                    json.name("world").value(p.getWorld().getName());
                    json.name("mode").value(p.getGameMode().name());
                    json.name("health").value(p.getHealth());
                    json.endObject();
                }
                json.endArray();
                json.endObject();
                future.complete(json.toString());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** 指定玩家的背包快照（主线程执行）。玩家不在线时返回 null。 */
    public CompletableFuture<String> captureAsync(UUID uuid, boolean ender) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    future.complete(null);
                    return;
                }
                future.complete(Snapshot.build(player, ender));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** 离线玩家列表：直接扫存档文件，按最后保存时间倒序。 */
    public CompletableFuture<String> captureOfflinePlayersAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            List<Path> dirs = OfflineData.playerdataDirs();
            Map<String, String> names = OfflineData.userCache();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    List<OfflineEntry> entries = new ArrayList<>();
                    for (Path file : OfflineData.playerFiles(dirs)) {
                        UUID uuid = OfflineData.uuidOf(file);
                        if (uuid == null) {
                            continue;
                        }
                        String id = uuid.toString();
                        String name = names.get(id);
                        if (name == null) {
                            name = id.substring(0, 8);
                        }
                        entries.add(new OfflineEntry(uuid, name, OfflineData.lastModified(file)));
                    }
                    entries.sort(Comparator.comparingLong(OfflineEntry::lastSeen).reversed());

                    Json json = new Json(8192);
                    json.beginObject();
                    json.name("ok").value(true);
                    json.name("offline").value(true);
                    json.name("time").value(System.currentTimeMillis());
                    json.name("count").value(entries.size());
                    json.name("players").beginArray();
                    int limit = Math.min(entries.size(), 300);
                    for (int i = 0; i < limit; i++) {
                        OfflineEntry e = entries.get(i);
                        json.beginObject();
                        json.name("name").value(e.name());
                        json.name("uuid").value(e.uuid().toString());
                        json.name("world").value("-");
                        json.name("mode").value("-");
                        json.name("health").value(0.0);
                        json.name("lastSeen").value(e.lastSeen());
                        json.endObject();
                    }
                    json.endArray();
                    json.endObject();
                    future.complete(json.toString());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        });
        return future;
    }

    /** 离线玩家背包：直接读 playerdata，不依赖玩家在线。找不到存档返回空串。 */
    public CompletableFuture<String> captureOfflineAsync(UUID uuid, String nameHint, boolean ender) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            List<Path> dirs = OfflineData.playerdataDirs();
            String hint = nameHint != null && !nameHint.isBlank() ? nameHint.trim() : null;
            String name = hint;
            if (name == null) {
                name = OfflineData.userCache().get(uuid.toString());
            }
            if (name == null) {
                name = displayName(Bukkit.getOfflinePlayer(uuid));
            }
            final String display = name;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    Path file = OfflineData.find(dirs, uuid);
                    if (file == null) {
                        future.complete(null);
                        return;
                    }
                    future.complete(OfflineData.snapshot(display, uuid, file, ender));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        });
        return future;
    }

    private record OfflineEntry(UUID uuid, String name, long lastSeen) {
    }

    private static String displayName(OfflinePlayer player) {
        String name = player.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String uuid = player.getUniqueId().toString();
        return uuid.substring(0, 8);
    }

    public static String detectLocalAddress() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 使用兜底
        }
        return "localhost";
    }

    public String baseUrl() {
        if (!settings.publicUrl().isBlank()) {
            return settings.publicUrl();
        }
        return "http://" + detectLocalAddress() + ":" + settings.port();
    }

    public String dashboardUrl() {
        return baseUrl() + "/?key=" + accessKey.value();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "link" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link", "open" -> {
                return sendLink(sender);
            }
            case "key" -> {
                return sendKey(sender);
            }
            case "newkey", "rotate" -> {
                return rotateKey(sender);
            }
            case "reload" -> {
                return reload(sender);
            }
            case "status" -> {
                return status(sender);
            }
            case "selftest" -> {
                return selfTest(sender);
            }
            default -> {
                sender.sendMessage(Component.text("/webbag [link|key|newkey|status|reload]", NamedTextColor.GRAY));
                return true;
            }
        }
    }

    private boolean sendLink(CommandSender sender) {
        if (http == null || !http.isRunning()) {
            sender.sendMessage(Component.text("网页服务未运行，请检查控制台日志。", NamedTextColor.RED));
            return true;
        }
        String url = dashboardUrl();
        sender.sendMessage(Component.text("网页背包总览（含全部在线玩家）：", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("点击打开"))));
        sender.sendMessage(Component.text("链接包含访问口令，请勿外传。", NamedTextColor.DARK_GRAY));
        return true;
    }

    private boolean sendKey(CommandSender sender) {
        sender.sendMessage(Component.text()
                .append(Component.text("访问口令：", NamedTextColor.GREEN))
                .append(Component.text(accessKey.value(), NamedTextColor.YELLOW))
                .build());
        sender.sendMessage(Component.text("完整链接：" + dashboardUrl(), NamedTextColor.GRAY));
        return true;
    }

    private boolean rotateKey(CommandSender sender) {
        String key = accessKey.rotate();
        getConfig().set("access-key", key);
        saveConfig();
        sender.sendMessage(Component.text("口令已更换，旧链接立即失效。", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("新口令：" + key, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("新链接：" + dashboardUrl(), NamedTextColor.AQUA));
        getLogger().info("Access key rotated by " + sender.getName() + ".");
        return true;
    }

    private boolean reload(CommandSender sender) {
        Settings old = settings;
        reloadConfig();
        settings = readSettings();

        String configured = getConfig().getString("access-key", "");
        if (configured != null && !configured.isBlank()) {
            if (!configured.trim().equals(accessKey.value())) {
                accessKey = new AccessKey(configured.trim());
                http.setAccessKey(accessKey);
            }
        }

        boolean needRestart = old.port() != settings.port() || !old.bind().equals(settings.bind());
        if (needRestart) {
            http.stop();
            if (http.start()) {
                sender.sendMessage(Component.text("配置已重载，服务已按新端口重启。", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("配置已重载，但端口启动失败，请查看控制台。", NamedTextColor.RED));
            }
        } else {
            http.rebuildPage();
            sender.sendMessage(Component.text("配置已重载。", NamedTextColor.GREEN));
        }
        return true;
    }

    private boolean status(CommandSender sender) {
        sender.sendMessage(Component.text("WebBag 状态", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("服务：" + (http != null && http.isRunning() ? "运行中" : "未运行"),
                http != null && http.isRunning() ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(Component.text("监听：" + settings.bind() + ":" + settings.port(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("地址：" + baseUrl(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("在线玩家：" + Bukkit.getOnlinePlayers().size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("末影箱：" + (settings.allowEnderChest() ? "可查看" : "已关闭"), NamedTextColor.GRAY));
        return true;
    }

    private boolean selfTest(CommandSender sender) {
        try {
            String json = Snapshot.sample();
            getLogger().info("Self-test snapshot: " + json);
            String coverage = Textures.coverage();
            getLogger().info("Self-test icon coverage: " + coverage);
            sender.sendMessage(Component.text("自检通过，JSON 长度 " + json.length() + " 字节。", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("图标覆盖：" + coverage, NamedTextColor.GRAY));
        } catch (Throwable t) {
            getLogger().severe("Self-test failed: " + t);
            sender.sendMessage(Component.text("自检失败：" + t.getMessage(), NamedTextColor.RED));
        }
        return true;
    }
}
