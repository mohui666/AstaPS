# MGC 服务器面板

安装后双击桌面 **MGC服务器面板** 快捷方式，或打开目录中的 **MGC服务器面板.html**。HTML 会进入完整网页控制台，可查看服务状态、在线人数、内存、CPU、日志，并使用启动、停止、重启按钮。

日常打开不经过 CMD、PowerShell 或 VBS。HTML 入口不能直接管理 Windows 进程；同目录的 Python 管理后台负责开关服，使用已有 `pythonw.exe` 隐藏运行，并通过当前用户的“启动”目录在登录时启动。管理后台启动不会自动开服，停服后面板仍可使用。

有玩家在线时不会停止或重启服务。原来的 `8088/console` 后台保留全部功能和登录方式，可从面板下方打开。`AstaPS 后台.url` 和“开服并进入原神”入口保留。

安装前预览桌面动作：

```powershell
powershell -NoProfile -File tools/server-panel/install-panel.ps1 -PlanOnly
```

确认后运行同一命令，去掉 `-PlanOnly`。PowerShell 仅用于安装：创建桌面文件夹、HTML 快捷方式和当前用户的登录启动项，并修复现有服务端入口。旧 `AstaPS 开服.cmd` 只打开 HTML，不再负责开服。已有同名非本工具目录会拒绝覆盖。

安装器会启动一次管理后台以生成可用的 HTML 入口；更新时仅在严格核对路径、会话及监听 PID 后替换本工具的 Python 后台，不会起停 Java 或 MongoDB。未被修改的旧工具 PS/VBS 启动器和旧 index.html 会移除，人工变更的文件保留。

面板使用本机已有 `C:\ProgramData\miniconda3\pythonw.exe`，无需安装依赖。面板目录内 `server_panel.py` 为管理后台，`.panel-ui.template` 为完整页面，`panel.config.json` 指向项目；`.panel-state.json` 和生成的 HTML 带有本机会话，两个 `.panel-*.log` 为启动诊断。不要公开这些本机文件。

管理后台每次启动会更新 HTML 中的本机会话。日常只需双击 HTML 入口；后台使用 `127.0.0.1:17966`，原游戏后台使用 `127.0.0.1:8088/console`。端口被其他程序占用或管理后台未启动时，应修复安装后再打开，不要使用旧会话链接。
