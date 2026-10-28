# MGC 7.1 一键连接包模板

配合完整的国服 Windows 7.1.0 客户端使用。此目录保留连接器源码；DLL 按 [客户端说明](../README.md) 编译。

1. 将本目录复制为分发文件夹，把编译生成的 `ext.dll`、`client/tools/patch_game.ps1`、`client/tools/game_path.ps1` 放入同一文件夹。
2. 放入对应服务器的 SDK 登录公钥，文件名为 `passport-public.pem`。沿用现有安装脚本的公钥格式；服务器私钥留在服务器端。
3. 将 `profile.example.json` 复制为 `profile.json`，填写服务器地址和已创建账号的资料。为每位朋友分配不同的新账号及密码；个人配置留在分发包中，不纳入源码提交。
4. 双击 `AstaPS-Connect.cmd`。首次在游戏登录界面手动输入账号和密码；连接器负责配置补丁与启动游戏。

`AstaPS-Check.cmd` 只检查国服 7.1.0 客户端、包内容和服务器状态；`AstaPS-Restore.cmd` 调用现有安装器还原客户端 DLL。PvP 模块默认启用，双方进入同一世界和场景后由主机启用配对。

`serverUrl` 只支持 `http://IPv4:port`。远程连接需要登录 HTTP API 的 TCP 映射及游戏 UDP 映射；所选线路必须允许这种 HTTP API。初次配置会请求客户端目录写入权限，并关闭客户端资源自动下载以保持 7.1 资源。