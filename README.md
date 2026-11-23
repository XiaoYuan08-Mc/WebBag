# WebBag

Purpur / Paper 26.3 的插件，用浏览器看服务器上在线玩家的背包和末影箱。只读，动不了任何物品。页面实时刷新，玩家进出、换装备、丢东西都跟着变；名字和头像取自玩家当前皮肤。

![界面预览](docs/preview.png)

## 安装

把 `WebBag-1.3.1.jar` 丢进 `plugins/`，重启。游戏里敲 `/webbag link`，聊天栏会给一条带口令的网址，浏览器打开就能看，左边是在线玩家列表，点谁看谁。

口令第一次访问后存在 Cookie 里（30 天），之后直接 `http://服务器IP:5001` 就行。口令自动生成在 `plugins/WebBag/config.yml`。

## 指令

- `/webbag` 当前网址和在线人数
- `/webbag link` 带口令的完整网址
- `/webbag key` / `newkey` 看口令 / 换口令（换完旧链接失效）
- `/webbag status` 端口、监听地址、请求数
- `/webbag reload` 重载配置
- `/webbag selftest` 打一份样例背包 JSON，排查贴图用

别名 `/wbag`、`/wbg`，权限 `webbag.admin`，默认仅 OP。

## 配置

`plugins/WebBag/config.yml`，常改的就几项：

- `port` 5001、`bind` 0.0.0.0
- `public-url` 对外网址，留空就用本机内网 IP
- `access-key` 口令，留空自动生成
- `live-update` 实时推送开关，`live-interval-ms` 推送间隔（默认 1 秒）
- `refresh-seconds` 兜底轮询，默认 10 秒，`0` 关掉
- `allow-ender-chest` 末影箱页签
- `assets-cdn` / `assets-version` 物品贴图和语言文件来源，版本要跟服务端对上

改完 `/webbag reload`，动端口得重启。

## 接口

除 `/health` 和 `/api/lang`，都要带 `key` 参数或 Cookie。

- `/api/players` 在线玩家：名字、UUID、世界、模式、生命
- `/api/inventory?player=<uuid>&type=inv|ender` 背包 / 末影箱
- `/api/skin?player=<uuid>` 当前皮肤裁出的 64×64 头像
- `/api/stream?player=<uuid>&type=inv|ender` SSE 实时流

槽位和原版一致：0–8 快捷栏，9–35 主背包，36–39 装备，40 副手。

## 注意

接口只读，没有写入口。口令 24 位随机，只走 HttpOnly Cookie，页面加载后会把网址里的 `?key=` 抹掉。单个 IP 默认每分钟 180 次请求。要开公网就套 Nginx + HTTPS，把 `bind` 改 127.0.0.1。

## 构建

JDK 25 加 Gradle 8，`gradle build`，产物在 `build/libs/`。
