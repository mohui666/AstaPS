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

## 来源与许可

服务端沿用根目录 AGPL-3.0 许可证。客户端补丁在 `patch/LICENSE` 的 GPL-3.0 许可证下提供；原始项目与修改说明见 [patch/README.md](patch/README.md)。保留 xeondev、oureveryday、capyb2222 及原项目贡献者的署名。

此目录只发布源码，不包含游戏文件、编译 DLL、账号、聊天记录、个人连接配置或运行日志。
