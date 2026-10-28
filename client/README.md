# Windows 7.1 客户端补丁源码

`patch/` 包含当前 7.1 补丁的 Rust 源码、依赖锁文件与许可证；`tools/` 包含部署和还原脚本。服务端仍按根目录说明编译运行。

## 编译

需要 Windows x64、Rust 1.97.1 及 Visual C++ 构建工具。在仓库根目录执行：

```powershell
cargo +1.97.1 build --release --locked --manifest-path client/patch/Cargo.toml
```

输出为 `client/patch/target/release/ext.dll`。测试使用：

```powershell
cargo +1.97.1 test --lib --locked --manifest-path client/patch/Cargo.toml
```

`ASTAPS_TEST_CLIENT` 可指定本机 `YuanShen.exe`，用于可选的客户端区段扫描测试；未设置时不读取游戏文件。

## 部署与启动

关闭游戏，在有游戏目录写入权限的 PowerShell 中执行，并将示例路径替换为自己的安装目录：

```powershell
$gameDirectory = 'D:\Games\Genshin Impact Game'
./client/tools/patch_game.ps1 -Mode apply -GameDirectory $gameDirectory -ServerUrl 'http://127.0.0.1:8088'

$env:ASTAPS_SERVER_URL = 'http://127.0.0.1:8088'
$env:LUNAGC_DISABLE = 'misc,console,ifix'
$env:LUNAGC_LOCAL_RECHARGE = '1'
Start-Process -FilePath (Join-Path $gameDirectory 'YuanShen.exe') -WorkingDirectory $gameDirectory
```

连接其他主机时，部署参数和 `ASTAPS_SERVER_URL` 必须使用同一个服务器地址。也可以在游戏可执行文件旁创建 `astaps-client.json`，内容为 `serverUrl` 和可选的 `localRecharge`，示例：

```json
{"serverUrl":"http://127.0.0.1:8088","localRecharge":true}
```

不提供 `-GameDirectory` 时，脚本从 `GAME_PATH`、仓库根目录的 `.env.local` / `.env` 或启动器注册表解析路径。查看或还原补丁：

```powershell
./client/tools/patch_game.ps1 -Mode status -GameDirectory $gameDirectory
./client/tools/patch_game.ps1 -Mode reset -GameDirectory $gameDirectory
```

部署脚本默认使用仓库的服务器登录公钥，连接自行更换密钥的服务器时，通过 `-PassportPublicKeyPath` 指定对应公钥。源码中的 `server_public_key.bin` 和 `sdk_public_key.xml` 都是公钥；服务器私钥不应分发给客户端。

## PVP

PVP 模块支持已核对的国服 Windows 7.1 客户端，只放行服务端标记的对战角色。命中仍经过原生碰撞和攻击计算；服务端校验攻击归属并结算护盾、无敌和死亡。换角色时更新标记，关闭 PVP 或离开场景时撤销。

双方进入同一世界和场景并安装补丁后，由管理员使用 `/pvp on @对手UID` 让 UID 1 与指定好友对战，或 `/pvp on @UID1 @UID2` 明确选择双方。可信 UID 1、2、3、4 中任意两人可开启一场双人对战；切换对手会撤销上一场标记和待结算命中。`/pvp off` 关闭，`/pvp status` 查看状态。好友参加对战无需管理员权限。未配对的跨玩家命中不会进入普通伤害结算。

不带参数的 `/pvp on` 保留原行为：优先选择 UID 1 已登记的测试机器人，否则选择 UID 1/2。`/pvp bot` 创建临时测试玩家，重复执行可添加多个目标，人数沿用世界上限；`/pvp removebot` 移除自己的全部测试玩家。机器人站在召唤者当前位置，召唤后退开几步再测试。

`LUNAGC_DISABLE` 增加 `pvp` 可禁用此模块。客户端样本或函数入口不匹配时，日志记录 `PvP native targeting disabled`，不会安装 PVP 钩子。弓箭、技能若使用独立 Gadget 作为攻击来源，需要实机核对其归属链。

## 来源与许可

服务端许可证见根目录 LICENSE。客户端补丁在 `patch/LICENSE` 的 GPL-3.0 许可证下提供；原始项目与修改说明见 [patch/README.md](patch/README.md)。保留 xeondev、oureveryday、capyb2222 及原项目贡献者的署名。

此目录只发布源码，不包含游戏文件、编译 DLL、账号、聊天记录、个人连接配置或运行日志。

## 好友连接包与远程映射

[package-template/](package-template/README.md) 提供本次连接包的通用源码和空白配置。每份个人包填写不同的已创建账号，首次在游戏内手动登录。连接器沿用现有安装/还原脚本，PvP 默认启用；账号权限仍由服务器管理。

当前补丁只接受 `http://IPv4:port`。远程映射需要同一节点上的 HTTP API TCP 入口与游戏 UDP 入口。若公网 UDP 端口与本地不同，设置 `server.game.accessPort` 为公网 UDP 端口，并将 `server.game.accessAddress`、`server.http.accessAddress` 留空：直接访问 bindAddress、127.0.0.1 或 localhost 时返回本地游戏端口，其他 Host 返回公网游戏端口。显式配置 accessAddress 或多个 regions 时保留原有端口优先规则。

保持 HTTP 的 `bindLoopback` 和控制台的 `webConsole.localOnly` 开启；隧道指向服务器实际 LAN 监听地址，控制台仍通过本机回环地址操作。个人账号密码、映射密钥和运行配置不随公开源码发布。
### 商城搜索邮件的公网入口

邮件生成时没有 HTTP 请求上下文。若公网映射的主机或端口与 LAN 监听地址不同，启动服务端时在 `-jar` 前指定 `-Dastaps.shopSearchUrl=http://203.0.113.10:52286`，将示例替换为实际公网 HTTP 源地址。该设置只控制商城搜索邮件的浏览器入口，不改变原有 LAN/公网游戏地址选择。未指定时沿用现有 HTTP accessAddress/accessPort 配置。

地址只包含 HTTP(S) 协议、主机和端口；个人令牌仍位于 `#token=...` 片段，页面通过原有请求头鉴权。更新配置后由玩家重新向助手发送“商城搜索”获取新邮件，旧邮件不会自动改写。