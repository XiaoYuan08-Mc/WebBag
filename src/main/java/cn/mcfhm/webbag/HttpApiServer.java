package cn.mcfhm.webbag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内置 HTTP 服务。除 /health、/api/lang 外均需 access-key（可用 ?key= 或 Cookie）。
 */
public final class HttpApiServer {

    private static final String COOKIE_NAME = "webbag_key";

    private record RateWindow(long start, int count) {
    }

    private final WebBagPlugin plugin;
    private volatile AccessKey accessKey;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpServer server;
    private ExecutorService executor;
    private volatile String pageHtml = "";
    private volatile String langCache;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final Semaphore streamSlots = new Semaphore(16);
    private final Skins skins;

    public HttpApiServer(WebBagPlugin plugin, AccessKey accessKey) {
        this.plugin = plugin;
        this.accessKey = accessKey;
        this.skins = new Skins(plugin);
    }

    public void setAccessKey(AccessKey accessKey) {
        this.accessKey = accessKey;
    }

    public synchronized boolean start() {
        WebBagPlugin.Settings settings = plugin.settings();
        try {
            rebuildPage();
            server = HttpServer.create(new InetSocketAddress(settings.bind(), settings.port()), 64);
            // 实时推送会长期占用线程，使用可增长的线程池
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "WebBag-HTTP");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handlePage);
            server.createContext("/api/players", this::handlePlayers);
            server.createContext("/api/inventory", this::handleInventory);
            server.createContext("/api/skin", this::handleSkin);
            server.createContext("/api/stream", this::handleStream);
            server.createContext("/api/lang", this::handleLang);
            server.createContext("/health", this::handleHealth);
            server.start();
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to bind " + settings.bind() + ":" + settings.port() + ": " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public void rebuildPage() {
        WebBagPlugin.Settings settings = plugin.settings();
        try (InputStream in = plugin.getResource("web/index.html")) {
            if (in == null) {
                pageHtml = "<!doctype html><meta charset=\"utf-8\"><title>WebBag</title><h1>missing web/index.html</h1>";
                return;
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            pageHtml = html
                    .replace("__ASSETS_CDN__", settings.assetsCdn())
                    .replace("__REFRESH__", String.valueOf(settings.refreshSeconds()))
                    .replace("__LIVE__", String.valueOf(settings.liveUpdate()))
                    .replace("__ALLOW_ENDER__", String.valueOf(settings.allowEnderChest()));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load web/index.html: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondText(exchange, 429, "请求过于频繁，请稍后再试。");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondText(exchange, 405, "只支持 GET。");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            respondText(exchange, 404, "未找到页面。");
            return;
        }
        // 首次带口令打开后记住口令，之后直接访问 http://ip:端口 即可
        String key = parseQuery(exchange.getRequestURI().getRawQuery()).get("key");
        if (key != null && accessKey != null && accessKey.matches(key)) {
            exchange.getResponseHeaders().add("Set-Cookie",
                    COOKIE_NAME + "=" + key + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax");
        }
        respond(exchange, 200, "text/html; charset=utf-8", pageHtml.getBytes(StandardCharsets.UTF_8));
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondJson(exchange, 429, errorJson("rate_limited", "请求过于频繁。"));
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, errorJson("method_not_allowed", "只支持 GET。"));
            return;
        }
        if (!authorized(exchange, parseQuery(exchange.getRequestURI().getRawQuery()))) {
            respondJson(exchange, 401, errorJson("invalid_key", "访问口令无效，请使用游戏内 /webbag 获取链接。"));
            return;
        }

        CompletableFuture<String> future = plugin.capturePlayersAsync();
        respondFuture(exchange, future);
    }

    private void handleInventory(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondJson(exchange, 429, errorJson("rate_limited", "请求过于频繁。"));
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, errorJson("method_not_allowed", "只支持 GET。"));
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (!authorized(exchange, query)) {
            respondJson(exchange, 401, errorJson("invalid_key", "访问口令无效，请使用游戏内 /webbag 获取链接。"));
            return;
        }

        boolean ender = "ender".equalsIgnoreCase(query.getOrDefault("type", "inv"));
        if (ender && !plugin.settings().allowEnderChest()) {
            respondJson(exchange, 403, errorJson("ender_disabled", "服务器已关闭末影箱查看。"));
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(query.getOrDefault("player", ""));
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, errorJson("bad_player", "player 参数需要玩家 UUID。"));
            return;
        }

        CompletableFuture<String> future = plugin.captureAsync(uuid, ender);
        String json;
        try {
            json = future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            respondJson(exchange, 504, errorJson("timeout", "服务器主线程繁忙，请稍后重试。"));
            return;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to build inventory snapshot: " + e);
            respondJson(exchange, 500, errorJson("internal_error", "生成背包数据失败。"));
            return;
        }
        if (json == null) {
            respondJson(exchange, 404, errorJson("player_offline", "该玩家已离线。"));
            return;
        }
        respond(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /** 玩家当前皮肤的头像（64×64 PNG）。 */
    private void handleSkin(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondJson(exchange, 429, errorJson("rate_limited", "请求过于频繁。"));
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, errorJson("method_not_allowed", "只支持 GET。"));
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (!authorized(exchange, query)) {
            respondJson(exchange, 401, errorJson("invalid_key", "访问口令无效。"));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(query.getOrDefault("player", ""));
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, errorJson("bad_player", "player 参数需要玩家 UUID。"));
            return;
        }
        byte[] png = skins.head(uuid);
        if (png == null) {
            respondJson(exchange, 404, errorJson("no_skin", "该玩家没有可用的皮肤贴图。"));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(png);
        }
        exchange.close();
    }

    /**
     * SSE 实时推送：每秒把在线玩家列表与目标玩家的背包变化推给浏览器，页面无需轮询。
     */
    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, errorJson("method_not_allowed", "只支持 GET。"));
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (!authorized(exchange, query)) {
            respondJson(exchange, 401, errorJson("invalid_key", "访问口令无效，请使用游戏内 /webbag 获取链接。"));
            return;
        }
        if (!plugin.settings().liveUpdate()) {
            respondJson(exchange, 404, errorJson("live_disabled", "实时推送已在配置中关闭。"));
            return;
        }
        boolean ender = "ender".equalsIgnoreCase(query.getOrDefault("type", "inv"));
        if (ender && !plugin.settings().allowEnderChest()) {
            respondJson(exchange, 403, errorJson("ender_disabled", "服务器已关闭末影箱查看。"));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(query.getOrDefault("player", ""));
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, errorJson("bad_player", "player 参数需要玩家 UUID。"));
            return;
        }
        if (!streamSlots.tryAcquire()) {
            respondJson(exchange, 503, errorJson("too_many_streams", "实时连接数已满，请稍后再试。"));
            return;
        }

        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);

            OutputStream out = exchange.getResponseBody();
            long interval = plugin.settings().liveIntervalMs();
            long deadline = System.currentTimeMillis() + 30 * 60_000L;
            String lastPlayers = null;
            boolean offlineSent = false;
            long lastWrite = System.currentTimeMillis();

            while (System.currentTimeMillis() < deadline) {
                String players;
                try {
                    players = plugin.capturePlayersAsync().get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    break;
                }
                if (players != null) {
                    String stable = stripTime(players);
                    if (!stable.equals(lastPlayers)) {
                        writeEvent(out, "players", players);
                        lastPlayers = stable;
                    }
                }

                String inv;
                try {
                    inv = plugin.captureAsync(uuid, ender).get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    break;
                }
                if (inv == null) {
                    if (!offlineSent) {
                        writeEvent(out, "offline", "{}");
                        offlineSent = true;
                    }
                } else {
                    offlineSent = false;
                    writeEvent(out, "inv", inv);
                }
                Thread.sleep(interval);
                long now = System.currentTimeMillis();
                if (now - lastWrite >= 15_000L) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastWrite = now;
                }
            }
            out.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 浏览器关闭页面 / 网络断开
        } catch (Exception e) {
            plugin.getLogger().warning("Live stream closed: " + e.getMessage());
        } finally {
            streamSlots.release();
            try {
                exchange.close();
            } catch (Exception ignored) {
                // 已关闭
            }
        }
    }

    private static void writeEvent(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\n" + "data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 去掉时间戳后再比较，避免每秒都推送未变化的在线列表。 */
    private static String stripTime(String json) {
        return json.replaceFirst("\"time\":\\d+,", "");
    }

    private void handleLang(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondJson(exchange, 429, errorJson("rate_limited", "请求过于频繁。"));
            return;
        }
        if (langCache == null) {
            langCache = downloadLang();
        }
        if (langCache == null) {
            respondJson(exchange, 503, errorJson("lang_unavailable", "语言文件暂时不可用。"));
            return;
        }
        respond(exchange, 200, "application/json; charset=utf-8", langCache.getBytes(StandardCharsets.UTF_8));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!allow(ipOf(exchange))) {
            respondJson(exchange, 429, errorJson("rate_limited", "请求过于频繁。"));
            return;
        }
        Json json = new Json(256);
        json.beginObject();
        json.name("ok").value(true);
        json.name("plugin").value("WebBag");
        json.name("port").value(plugin.settings().port());
        json.name("time").value(System.currentTimeMillis());
        json.endObject();
        respond(exchange, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 口令可以放在查询参数 ?key=，也可以由页面首次访问时写入 Cookie。 */
    private boolean authorized(HttpExchange exchange, Map<String, String> query) {
        if (accessKey == null || accessKey.isEmpty()) {
            return false;
        }
        String key = query.get("key");
        if (key == null || key.isEmpty()) {
            key = cookieValue(exchange, COOKIE_NAME);
        }
        return accessKey.matches(key);
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.isEmpty()) {
            return null;
        }
        for (String part : header.split(";")) {
            String item = part.trim();
            int idx = item.indexOf('=');
            if (idx > 0 && item.substring(0, idx).equals(name)) {
                return item.substring(idx + 1);
            }
        }
        return null;
    }

    private void respondFuture(HttpExchange exchange, CompletableFuture<String> future) throws IOException {
        try {
            String json = future.get(5, TimeUnit.SECONDS);
            if (json == null) {
                respondJson(exchange, 404, errorJson("player_offline", "玩家已离线。"));
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            respondJson(exchange, 504, errorJson("timeout", "服务器主线程繁忙，请稍后重试。"));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to build response: " + e);
            respondJson(exchange, 500, errorJson("internal_error", "数据生成失败。"));
        }
    }

    private String downloadLang() {
        String url = plugin.settings().assetsCdn() + "/lang/zh_cn.json";
        try {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "WebBag/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 && !response.body().isBlank()) {
                plugin.getLogger().info("Cached zh_cn language file (" + response.body().length() + " bytes).");
                return response.body();
            }
            plugin.getLogger().warning("Language file returned status " + response.statusCode() + ": " + url);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch language file: " + e.getMessage());
        }
        return null;
    }

    private boolean allow(String ip) {
        int limit = plugin.settings().rateLimitPerMinute();
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.compute(ip, (key, old) -> {
            if (old == null || now - old.start() > 60_000L) {
                return new RateWindow(now, 1);
            }
            return new RateWindow(old.start(), old.count() + 1);
        });
        if (rateWindows.size() > 20_000) {
            rateWindows.clear();
        }
        return window.count() <= limit;
    }

    private static String ipOf(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key;
            String value;
            if (idx < 0) {
                key = pair;
                value = "";
            } else {
                key = pair.substring(0, idx);
                value = pair.substring(idx + 1);
            }
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String errorJson(String code, String message) {
        Json json = new Json(256);
        json.beginObject();
        json.name("ok").value(false);
        json.name("error").value(code);
        json.name("message").value(message);
        json.endObject();
        return json.toString();
    }

    private void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void respondText(HttpExchange exchange, int status, String text) throws IOException {
        respond(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
