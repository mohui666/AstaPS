"""Local MGC process panel. Uses only Python's standard library and existing runtimes."""

from __future__ import annotations

import argparse
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


APP = "mgc-server-panel"
LOOPBACK = "127.0.0.1"
NETWORK_ERRORS = (URLError, TimeoutError, OSError, http.client.HTTPException)


class PanelError(Exception):
    def __init__(self, message: str, status: int = 409):
        super().__init__(message)
        self.status = status


def read_json(path: Path):
    with path.open(encoding="utf-8-sig") as stream:
        return json.load(stream)


def port_number(value) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 65535:
        raise PanelError("端口配置无效。", 500)
    return value


def argument_path(command: str, flag: str) -> Path | None:
    match = re.search(r"(?:^|\s)" + re.escape(flag) + r'\s+(?:"([^"]+)"|(\S+))', command)
    if not match:
        return None
    result = Path(match.group(1) or match.group(2))
    return result.resolve() if result.is_absolute() else None


def same_path(first, second) -> bool:
    return str(Path(first).resolve()).casefold() == str(Path(second).resolve()).casefold()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


class JavaConsole:
    def __init__(self):
        # Local credentials stay on loopback, without system proxies or redirects.
        self.opener = build_opener(ProxyHandler({}), NoRedirect)

    def request(self, port: int, path: str, body=None, token=None, timeout=3):
        headers = {"Accept": "application/json"}
        if token is not None:
            headers["X-Console-Token"] = token
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        request = Request(f"http://{LOOPBACK}:{port}{path}", data=data, headers=headers)
        with self.opener.open(request, timeout=timeout) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise PanelError("后台响应过大。", 502)
        result = json.loads(data.decode("utf-8"))
        if not isinstance(result, dict):
            raise PanelError("后台响应格式无效。", 502)
        return result

    def status(self, port: int):
        try:
            return self.request(port, "/api/status", timeout=1.5)
        except (HTTPError, *NETWORK_ERRORS, ValueError, PanelError):
            return None

    def login(self, port: int, password: str) -> str:
        try:
            result = self.request(port, "/console/api/login", {"password": password})
        except HTTPError as error:
            raise PanelError("后台登录失败，请检查本机 webConsole 配置。", 502) from error
        token = result.get("token")
        if not isinstance(token, str) or not token:
            raise PanelError("后台未返回有效会话。", 502)
        return token

    def stop(self, port: int, token: str):
        try:
            self.request(port, "/console/api/command", {"command": "stop"}, token)
        except HTTPError as error:
            raise PanelError("后台拒绝了停止命令。", 502) from error


class WindowsProcesses:
    """Read live process/port ownership; a saved PID is never used as authority."""

    @staticmethod
    def hidden_options():
        return {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}

    def inspect(self, http_port: int):
        if os.name != "nt":
            raise PanelError("开服进程管理需要 Windows。", 500)
        script = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
  Where-Object { $_.LocalPort -in @(__HTTP_PORT__,27017) } |
  Select-Object @{n='port';e={$_.LocalPort}},@{n='pid';e={$_.OwningProcess}})
$processes = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'mongod.exe'" |
  Select-Object @{n='pid';e={$_.ProcessId}},@{n='exe';e={$_.ExecutablePath}},
    @{n='command';e={$_.CommandLine}},@{n='created';e={$_.CreationDate.ToString('o')}})
@{listeners=$listeners;processes=$processes} | ConvertTo-Json -Depth 4 -Compress
""".replace("__HTTP_PORT__", str(port_number(http_port)))
        try:
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script],
                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15,
                check=True, **self.hidden_options(),
            )
            data = json.loads(result.stdout)
            if not isinstance(data.get("processes"), list) or not isinstance(data.get("listeners"), list):
                raise ValueError("invalid process snapshot")
            return data
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            raise PanelError("无法读取 Windows 进程与端口状态。", 500) from error

    def start(self, executable: Path, arguments: list[str], project: Path, stdout: Path | None = None,
              stderr: Path | None = None):
        if not executable.is_file():
            raise PanelError("已有运行环境中的可执行文件不存在。", 500)
        if stdout is None:
            return subprocess.Popen([str(executable), *arguments], cwd=project, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **self.hidden_options())
        with stdout.open("ab") as output, stderr.open("ab") as errors:
            return subprocess.Popen([str(executable), *arguments], cwd=project, stdin=subprocess.DEVNULL,
                                    stdout=output, stderr=errors, **self.hidden_options())


class PanelController:
    def __init__(self, config_path: Path, processes=None, console=None, *, startup_timeout=180, stop_timeout=60):
        self.config_path = config_path.resolve()
        self.folder = self.config_path.parent
        config = read_json(self.config_path)
        self.project = Path(config["projectRoot"]).resolve()
        if not self.project.is_dir():
            raise PanelError("配置的 MGC 项目目录不存在。", 500)
        self.port = port_number(config.get("port", 17966))
        self.jar = self.project / "grasscutter-7.1.0.jar"
        self.token = secrets.token_urlsafe(32)
        self.processes = processes or WindowsProcesses()
        self.console = console or JavaConsole()
        self.startup_timeout = startup_timeout
        self.stop_timeout = stop_timeout
        self.action_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.phase = None
        self.message = ""
        self.worker = None
        self.console_token = None

    def write_entry(self):
        template = (self.folder / ".panel-ui.template").read_text(encoding="utf-8")
        placeholder = '<script id="panel-config" type="application/json">{}</script>'
        if template.count(placeholder) != 1:
            raise PanelError("面板页面缺少本机连接配置。", 500)
        config = json.dumps({"apiBase": f"http://{LOOPBACK}:{self.port}", "token": self.token})
        html = template.replace(placeholder, f'<script id="panel-config" type="application/json">{config}</script>')
        (self.folder / "MGC服务器面板.html").write_text(html, encoding="utf-8")

    def settings(self):
        try:
            server = read_json(self.project / "config.json").get("server", {}).get("http", {})
            environment = read_json(self.project / "local" / "environment.json")
            return {
                "http_port": port_number(server.get("bindPort", 8088)),
                "password": server.get("webConsole", {}).get("password"),
                "console_enabled": server.get("webConsole", {}).get("enabled", False),
                "java": Path(environment["javaHome"]) / "bin" / "java.exe",
                "mongo": Path(environment["mongoHome"]) / "bin" / "mongod.exe",
                "mongo_config": self.project / "local" / "mongod.cfg",
            }
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise PanelError("无法读取现有服务端配置和运行环境。", 500) from error

    def redact(self, value: str) -> str:
        sensitive = [self.token, self.console_token]
        try:
            sensitive.append(self.settings()["password"])
        except PanelError:
            pass
        for secret in sensitive:
            if isinstance(secret, str) and secret:
                value = value.replace(secret, "[已隐藏]")
        return value

    def verified_java(self, process, settings):
        executable = process.get("exe")
        jar = argument_path(process.get("command") or "", "-jar")
        if not executable or not same_path(executable, settings["java"]) or jar is None:
            return None
        if not same_path(jar.parent, self.project) or not jar.name.lower().startswith("grasscutter-") or jar.suffix.lower() != ".jar":
            return None
        return {**process, "jar": str(jar)}

    def facts(self):
        settings = self.settings()
        snapshot = self.processes.inspect(settings["http_port"])
        candidates = [verified for process in snapshot["processes"]
                      if (verified := self.verified_java(process, settings)) is not None]
        owners = {row["pid"] for row in snapshot["listeners"] if row["port"] == settings["http_port"]}
        if owners and (len(owners) != 1 or not any(row["pid"] in owners for row in candidates)):
            raise PanelError("服务端 HTTP 端口由其他程序占用，未操作该进程。")
        if len(candidates) > 1:
            raise PanelError("检测到多个 MGC Java 进程，请先确认需要管理的实例。")
        process = candidates[0] if candidates else None
        metrics = self.console.status(settings["http_port"]) if process and owners else None
        return settings, snapshot, process, metrics

    @staticmethod
    def online_count(metrics):
        if not isinstance(metrics, dict) or metrics.get("retcode") != 0:
            return None
        game = metrics.get("game")
        count = game.get("players") if isinstance(game, dict) else None
        return count if isinstance(count, int) and not isinstance(count, bool) and count >= 0 else None

    def status(self):
        try:
            _, _, process, metrics = self.facts()
            observed = "running" if process and self.online_count(metrics) is not None else "starting" if process else "stopped"
            message = {"running": "已连接本机服务端。", "starting": "正在等待服务端后台就绪。",
                       "stopped": "服务端未运行。"}[observed]
        except PanelError as error:
            process, metrics, observed, message = None, None, "error", str(error)
        with self.state_lock:
            state = self.phase or observed
            message = self.message or message
        return {"server": {"state": state, "pid": process["pid"] if process else None,
                           "jar": process["jar"] if process else str(self.jar)},
                "metrics": metrics, "message": self.redact(message)}

    def request_action(self, action: str):
        if action not in {"start", "stop", "restart"}:
            raise PanelError("未知操作。", 404)
        if not self.action_lock.acquire(blocking=False):
            raise PanelError("已有开服或关服操作正在执行，请等待状态更新。")
        handed_off = False
        try:
            _, _, process, metrics = self.facts()
            if action == "start" and process:
                return 200, self.status()
            if action in {"stop", "restart"} and process:
                self.require_empty(metrics)
            if action == "stop" and process is None:
                return 200, self.status()
            with self.state_lock:
                self.phase = "stopping" if process and action in {"stop", "restart"} else "starting"
                self.message = "正在保存并停止服务端。" if self.phase == "stopping" else "正在启动服务端。"
            self.worker = threading.Thread(target=self._perform, args=(action, process), name="mgc-panel-action", daemon=True)
            self.worker.start()
            handed_off = True
            return 202, self.status()
        finally:
            if not handed_off:
                self.action_lock.release()

    @staticmethod
    def require_empty(metrics):
        count = PanelController.online_count(metrics)
        if count is None:
            raise PanelError("无法确认在线人数，未停止服务端；请检查后台状态。")
        if count:
            raise PanelError(f"当前有 {count} 名玩家在线，未执行关服或重启。")

    def _perform(self, action, process):
        try:
            if process and action in {"stop", "restart"}:
                self._stop(process)
            if action in {"start", "restart"}:
                with self.state_lock:
                    self.phase, self.message = "starting", "正在启动服务端。"
                self._start()
            with self.state_lock:
                self.phase, self.message = None, ""
        except PanelError as error:
            with self.state_lock:
                self.phase, self.message = None, str(error)
        except (OSError, ValueError, subprocess.SubprocessError, *NETWORK_ERRORS):
            with self.state_lock:
                self.phase, self.message = None, "操作未完成，请查看服务端日志；未强制终止任何进程。"
        finally:
            self.console_token = None
            self.action_lock.release()

    @staticmethod
    def same_process(first, second):
        return second is not None and all(first.get(key) == second.get(key) for key in ("pid", "created", "exe", "jar"))

    def _stop(self, expected):
        settings, _, process, metrics = self.facts()
        if not self.same_process(expected, process):
            raise PanelError("运行进程已变化，未发送停止命令。")
        self.require_empty(metrics)
        password = settings["password"]
        if not settings["console_enabled"] or not isinstance(password, str) or not password.strip():
            raise PanelError("现有 webConsole 未启用或未配置密码，无法优雅关服。")
        self.console_token = self.console.login(settings["http_port"], password)
        # Recheck process identity and players after authentication, immediately before stop.
        _, _, current, metrics = self.facts()
        if not self.same_process(expected, current):
            raise PanelError("运行进程已变化，未发送停止命令。")
        self.require_empty(metrics)
        try:
            self.console.stop(settings["http_port"], self.console_token)
        except NETWORK_ERRORS:
            # stop can close HTTP before its response. Only an observed process exit is success.
            with self.state_lock:
                self.message = "停止命令后连接已关闭，正在确认服务端进程退出。"
        deadline = time.monotonic() + self.stop_timeout
        while time.monotonic() < deadline:
            current = self.processes.inspect(settings["http_port"])
            same = any(row.get("pid") == expected["pid"] and row.get("created") == expected.get("created")
                       for row in current["processes"])
            listening = any(row["port"] == settings["http_port"] for row in current["listeners"])
            if not same and not listening:
                return
            time.sleep(.5)
        raise PanelError("服务端未在等待时间内退出；未强杀进程，请查看日志。")

    def _start(self):
        settings, snapshot, process, _ = self.facts()
        if process:
            raise PanelError("已有 MGC 实例运行，未重复启动。")
        if not self.jar.is_file() or not settings["mongo_config"].is_file():
            raise PanelError("现有服务端 JAR 或 MongoDB 配置缺失。", 500)
        mongo_owners, mongo = self.mongo_state(snapshot, settings)
        if not mongo_owners:
            if not mongo:
                (self.project / "local" / "mongodb" / "data").mkdir(parents=True, exist_ok=True)
                self.processes.start(settings["mongo"], ["--config", str(settings["mongo_config"])], self.project)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                snapshot = self.processes.inspect(settings["http_port"])
                if self.mongo_state(snapshot, settings)[0]:
                    break
                time.sleep(.5)
            else:
                raise PanelError("MongoDB 未启动，请查看 local/mongodb/mongod.log。")
        local = self.project / "local"
        local.mkdir(exist_ok=True)
        self.processes.start(settings["java"], ["-Xms512m", "-Xmx4g", "-Dfile.encoding=UTF-8",
                             "-jar", str(self.jar)], self.project,
                             local / "server-stdout.log", local / "server-stderr.log")
        deadline = time.monotonic() + self.startup_timeout
        while time.monotonic() < deadline:
            _, _, process, metrics = self.facts()
            if process and self.online_count(metrics) is not None:
                return
            if not process:
                raise PanelError("Java 进程已退出，请查看服务端日志。")
            time.sleep(1)
        raise PanelError("服务端仍未就绪，请查看启动日志；未强制终止进程。")

    @staticmethod
    def mongo_state(snapshot, settings):
        owners = {row["pid"] for row in snapshot["listeners"] if row["port"] == 27017}
        matching = [row for row in snapshot["processes"] if row.get("exe")
                    and same_path(row["exe"], settings["mongo"])
                    and (config := argument_path(row.get("command") or "", "--config")) is not None
                    and same_path(config, settings["mongo_config"])]
        if owners and (len(owners) != 1 or not any(row["pid"] in owners for row in matching)):
            raise PanelError("27017 端口由其他数据库实例占用，未操作它。")
        return owners, matching

    def logs(self, count: int):
        if not 1 <= count <= 1000:
            raise PanelError("日志行数须为 1–1000。", 400)
        with self.state_lock:
            starting = self.phase == "starting"
        path = self.project / ("local/server-stdout.log" if starting else "logs/latest.log")
        if not path.is_file():
            path = self.project / "local" / "server-stdout.log"
        if not path.is_file():
            return {"lines": []}
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            position, blocks, newlines = stream.tell(), [], 0
            while position > 0 and newlines <= count:
                size = min(position, 64 * 1024)
                position -= size
                stream.seek(position)
                block = stream.read(size)
                blocks.append(block)
                newlines += block.count(b"\n")
        text = b"".join(reversed(blocks)).decode("utf-8", errors="replace")
        return {"lines": [self.redact(line) for line in text.splitlines()[-count:]]}


def make_handler(controller: PanelController):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            # Access logging would retain session-bearing URLs from an accidental launcher change.
            pass

        def send_json(self, code, value):
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.file_cors_headers()
            self.end_headers()
            self.wfile.write(body)

        def local_host(self):
            host = self.headers.get("Host", "").lower()
            allowed = {f"127.0.0.1:{controller.port}", f"localhost:{controller.port}"}
            return self.client_address[0] in {"127.0.0.1", "::1"} and host in allowed

        def same_origin(self):
            if not self.local_host():
                return False
            host = self.headers.get("Host", "").lower()
            origin = self.headers.get("Origin")
            if origin is not None and origin != "http://" + host:
                return False
            return self.headers.get("Sec-Fetch-Site", "same-origin") in {"same-origin", "none"}

        def file_request(self):
            # file:// has an opaque Origin. Every API call still needs the private token.
            return (self.local_host() and self.headers.get("Origin") == "null"
                    and self.headers.get("Sec-Fetch-Mode") == "cors"
                    and self.headers.get("Sec-Fetch-Dest") == "empty"
                    and self.headers.get("Sec-Fetch-Site") in {"cross-site", "none"})

        def file_cors_headers(self):
            if self.file_request():
                self.send_header("Access-Control-Allow-Origin", "null")
                self.send_header("Vary", "Origin")

        def authorized(self):
            return (self.same_origin() or self.file_request()) and hmac.compare_digest(
                self.headers.get("X-Panel-Token", "").encode("utf-8"), controller.token.encode("utf-8"))

        def do_OPTIONS(self):
            path = urlsplit(self.path).path
            method = self.headers.get("Access-Control-Request-Method")
            headers = {item.strip().lower() for item in self.headers.get("Access-Control-Request-Headers", "").split(",")}
            route = ((method == "GET" and path in {"/api/status", "/api/logs"})
                     or (method == "POST" and path in {"/api/start", "/api/stop", "/api/restart"}))
            # Browser preflights omit the token; require it on the following actual request.
            if not (self.file_request() and route and "x-panel-token" in headers
                    and headers <= {"x-panel-token", "content-type"}):
                self.send_json(403, {"error": "不允许此跨来源请求。"})
                return
            self.send_response(204)
            self.file_cors_headers()
            self.send_header("Access-Control-Allow-Methods", method)
            self.send_header("Access-Control-Allow-Headers", "X-Panel-Token, Content-Type")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def public_navigation(self):
            host = self.headers.get("Host", "").lower()
            return (self.client_address[0] in {"127.0.0.1", "::1"}
                    and host in {f"127.0.0.1:{controller.port}", f"localhost:{controller.port}"}
                    and self.headers.get("Origin") is None
                    and self.headers.get("Sec-Fetch-Mode") == "navigate"
                    and self.headers.get("Sec-Fetch-Dest") == "document")

        def do_GET(self):
            path = urlsplit(self.path)
            if path.path == "/healthz":
                self.send_json(200, {"app": APP})
                return
            if path.path == "/":
                if not (self.same_origin() or self.public_navigation()):
                    self.send_json(403, {"error": "仅允许本机同源访问。"})
                    return
                try:
                    body = (controller.folder / ".panel-ui.template").read_bytes()
                except OSError:
                    self.send_json(404, {"error": "面板页面不存在。"})
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Frame-Options", "DENY")
                self.send_header("Referrer-Policy", "no-referrer")
                self.end_headers()
                self.wfile.write(body)
                return
            if not self.authorized():
                self.send_json(403, {"error": "面板会话无效或来源不匹配，请从桌面入口重新打开。"})
                return
            try:
                if path.path == "/api/status":
                    self.send_json(200, controller.status())
                elif path.path == "/api/logs":
                    count = int(parse_qs(path.query).get("lines", ["100"])[0])
                    self.send_json(200, controller.logs(count))
                else:
                    self.send_json(404, {"error": "未知路径。"})
            except (ValueError, PanelError) as error:
                self.send_json(error.status if isinstance(error, PanelError) else 400,
                               {"error": controller.redact(str(error)) if isinstance(error, PanelError) else "日志行数无效。"})

        def do_POST(self):
            if not self.authorized():
                self.send_json(403, {"error": "面板会话无效或来源不匹配，请从桌面入口重新打开。"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 1024:
                    raise PanelError("请求内容无效。", 400)
                if json.loads(self.rfile.read(length)) != {}:
                    raise PanelError("此操作不接受额外参数。", 400)
                action = {"/api/start": "start", "/api/stop": "stop", "/api/restart": "restart"}.get(self.path)
                if action is None:
                    raise PanelError("未知操作。", 404)
                code, body = controller.request_action(action)
                self.send_json(code, body)
            except PanelError as error:
                self.send_json(error.status, {"error": controller.redact(str(error))})
            except (ValueError, UnicodeError):
                self.send_json(400, {"error": "请求内容无效。"})

    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description="MGC local server panel")
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("panel.config.json"))
    arguments = parser.parse_args(argv)
    controller = PanelController(arguments.config)
    server = ThreadingHTTPServer((LOOPBACK, controller.port), make_handler(controller))
    server.daemon_threads = True
    state = {"app": APP, "pid": os.getpid(), "port": controller.port, "token": controller.token}
    state_file = controller.folder / ".panel-state.json"
    temporary = controller.folder / ".panel-state.tmp"
    temporary.write_text(json.dumps(state), encoding="utf-8")
    temporary.replace(state_file)
    controller.write_entry()
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    try:
        main()
    except (PanelError, OSError, ValueError, KeyError) as error:
        print(str(error) if isinstance(error, PanelError) else "MGC 面板启动失败，请检查配置和端口。", file=sys.stderr)
        sys.exit(1)
