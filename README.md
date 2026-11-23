# WebBag

Purpur / Paper 26.3 的插件，用浏览器看服务器上玩家的背包和末影箱，在线的、离线的都能看。只读，动不了任何物品。在线玩家实时刷新，玩家进出、换装备、丢东西都跟着变；离线玩家读存档文件，显示的是他下线上一次保存的状态。名字和头像取自玩家皮肤。



![界面预览](docs/preview.png)

## 安装

把 `WebBag-1.4.2.jar` 丢进 `plugins/`，重启。游戏里敲 `/webbag link`，聊天栏会给一条带口令的网址，浏览器打开就能看。左边是玩家列表，点谁看谁。

口令第一次访问后存在 Cookie 里（30 天），之后直接 `http://服务器IP:5001` 就行。口令自动生成在 `plugins/WebBag/config.yml`。

## 离线玩家

列表左上角有「在线 / 离线」切换。离线页直接扫 `world/players`（26.3）或 `world/playerdata`（旧版）里的存档文件，列出所有有存档的玩家，按最后保存时间倒序，点开看那个时候的背包、装备和末影箱。名字取自 `usercache.json`。

离线数据是静态的，不推流，页面上点「刷新」重新读一次。服务器里没有存档文件的玩家不会出现在离线列表里。

## 指令



* `/webbag` 当前网址和在线人数

* `/webbag link` 带口令的完整网址

* `/webbag key` / `newkey` 看口令 / 换口令（换完旧链接失效）

* `/webbag status` 端口、监听地址、在线人数

* `/webbag reload` 重载配置

* `/webbag selftest` 打一份样例背包 JSON，排查贴图用

别名 `/wbag`、`/wbg`，权限 `webbag.admin`，默认仅 OP。

## 配置

`plugins/WebBag/config.yml`，常改的就几项：



* `port` 5001、`bind` 0.0.0.0

* `public-url` 对外网址，留空就用本机内网 IP

* `access-key` 口令，留空自动生成

* `live-update` 实时推送开关，`live-interval-ms` 推送间隔（默认 1 秒）

* `refresh-seconds` 兜底轮询，默认 10 秒，`0` 关掉

* `allow-ender-chest` 末影箱页签

* `assets-cdn` / `assets-version` 语言文件来源（图标已经打包在 jar 里，不依赖它），版本要跟服务端对上

改完 `/webbag reload`，动端口得重启。

## 接口

除 `/health` 和 `/api/lang`，都要带 `key` 参数或 Cookie。



* `/api/players` 在线玩家：名字、UUID、世界、模式、生命

* `/api/players?offline=1` 离线玩家：名字、UUID、最后保存时间

* `/api/inventory?player=<uuid>&type=inv|ender` 背包 / 末影箱

* `/api/inventory?player=<uuid>&type=inv|ender&offline=1` 读存档的背包 / 末影箱

* `/api/skin?player=<uuid>` 当前皮肤裁出的 64×64 头像

* `/api/tex?id=<物品名>` 物品图标 PNG，直接读 jar 内的原版贴图，不联网

* `/api/stream?player=<uuid>&type=inv|ender` SSE 实时流，只在在线玩家用

槽位和原版一致：0–8 快捷栏，9–35 主背包，36–39 装备，40 副手。地址后面加 `&scope=offline` 可以直接打开离线页，`&player=<uuid 或玩家名>` 直接打开某个人的背包。

## 注意

接口只读，没有写入口。离线读的是磁盘上的存档，玩家在线时读到的是他上一次存盘的状态，不是当前背包。口令 24 位随机，只走 HttpOnly Cookie，页面加载后会把网址里的 `?key=` 抹掉。单个 IP 默认每分钟 180 次请求。要开公网就套 Nginx + HTTPS，把 `bind` 改 127.0.0.1。

## 构建

JDK 25 加 Gradle 8，`gradle build`，产物在 `build/libs/`。