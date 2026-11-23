# WebBag

Minecraft 插件（Purpur / Paper 26.3，Java 25）。在浏览器里查看服务器上**所有在线玩家**的背包和末影箱，管理员一个口令就能全服翻看，只读、不修改任何数据。页面通过 SSE 实时推送，玩家换装备、丢东西、进出服务器都会立刻反映出来。列表和面板里的名字、头像直接取服务器上的玩家数据，头像用的就是该玩家当前的皮肤。

![界面预览](docs/preview.png)

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Purpur / Paper 26.3 |
| Java | 25（26.x 强制要求） |
| 依赖 | 无，插件内置 HTTP 服务 |

## 安装

1. 把 `WebBag-1.3.0.jar` 放进服务端 `plugins/`。
2. 重启服务器。日志出现 `Web inventory dashboard listening on 0.0.0.0:5001` 即启动成功。
3. 游戏内执行 `/webbag link`，聊天栏会给出一条带口令的完整网址。
4. 浏览器打开该网址，左侧列出在线玩家，点谁看谁。

打开一次后口令会记住在浏览器里（Cookie，30 天），以后直接访问 `http://服务器IP:5001` 就能看，不用再敲指令、也不用带 `?key=`。

首次启动会在 `plugins/WebBag/config.yml` 自动生成 24 位访问口令并写回文件。

## 配置 `plugins/WebBag/config.yml`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `port` | `5001` | 网页端口 |
| `bind` | `0.0.0.0` | 监听地址，改 `127.0.0.1` 可只允许本机（配合反代） |
| `public-url` | 空 | 对外网址，例如 `https://bag.example.com`；留空则用本机内网 IP |
| `access-key` | 空 | 访问口令，留空则首次启动自动生成 |
| `allow-ender-chest` | `true` | 是否显示末影箱页签 |
| `assets-cdn` | jsDelivr | 物品贴图与原版语言文件来源 |
| `assets-version` | `26.3` | 贴图版本，需与服务端一致 |
| `live-update` | `true` | 实时推送开关，关闭后退回定时轮询 |
| `live-interval-ms` | `1000` | 推送间隔（毫秒），最低 500 |
| `refresh-seconds` | `10` | 兜底轮询间隔（推送不可用时生效），`0` 关闭 |
| `rate-limit-per-minute` | `180` | 单 IP 每分钟请求上限 |

改完执行 `/webbag reload`，端口变更需重启。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/webbag` | 显示当前页面地址与在线人数 |
| `/webbag link` | 输出带口令的完整网址 |
| `/webbag key` | 查看当前口令 |
| `/webbag newkey` | 重新生成口令，旧链接立刻失效 |
| `/webbag status` | 端口、监听地址、请求计数等状态 |
| `/webbag selftest` | 输出样例背包 JSON，用于排查贴图/字段问题 |
| `/webbag reload` | 重载配置 |

别名：`/wbag`、`/wbg`。权限 `webbag.admin`，默认仅 OP。

## HTTP 接口

除 `/health` 与 `/api/lang` 外均需 `key` 参数，缺失或错误返回 `401`。

| 路径 | 说明 |
| --- | --- |
| `/` | 页面，`?key=` 带上口令（首次访问后写入 Cookie，之后可省略） |
| `/api/players` | 在线玩家列表：名字、UUID、世界、模式、生命 |
| `/api/inventory?player=<uuid>&type=inv\|ender` | 单个玩家背包 / 末影箱；UUID 非法返回 `400` |
| `/api/skin?player=<uuid>` | 该玩家当前皮肤裁剪出的 64×64 头像 PNG，无皮肤返回 `404` |
| `/api/stream?player=<uuid>&type=inv\|ender` | SSE 实时流：`players`（在线列表变化）、`inv`（背包变化）、`offline`（玩家离线） |
| `/api/lang` | 原版 `zh_cn` 语言文件（插件缓存后本地代理） |
| `/health` | 存活检查 |

鉴权：查询参数 `key=` 或 Cookie `webbag_key` 任一有效即可，缺失返回 `401`。实时流最长保持 30 分钟，期间每 15 秒发一次心跳，浏览器会自动续连。

槽位与原版一致：`0–8` 快捷栏，`9–35` 主背包，`36` 头盔、`37` 胸甲、`38` 护腿、`39` 靴子、`40` 副手。

## 安全说明

- 只读：接口没有任何写入路径，不会改动玩家物品。
- 口令为 24 位随机串，服务端比对用常量时间比较，不落日志。
- 口令只写在 Cookie（HttpOnly，30 天），页面加载后会自动把网址里的 `?key=` 去掉。
- 单个 IP 默认每分钟 180 次请求上限，实时流并发上限 16 条。
- 暴露公网建议套 Nginx 反代 + HTTPS，并把 `bind` 改成 `127.0.0.1`。

## 构建

需要 JDK 25 与 Gradle 8+：

```
set JAVA_HOME=<jdk-25 路径>
gradle build
```

产物 `build/libs/WebBag-1.3.0.jar`。

## 结构

```
src/main/java/cn/mcfhm/webbag/
  WebBagPlugin.java   指令、配置、快照调度
  HttpApiServer.java  HTTP 路由与限流
  Snapshot.java       背包/末影箱数据读取
  Skins.java          读取玩家皮肤链接并缓存头像
  HeadCutter.java     皮肤图裁成头像（不依赖服务端 API）
  AccessKey.java      口令生成与校验
  Json.java           极简 JSON 序列化
src/main/resources/
  plugin.yml  config.yml  web/index.html
```
