"""Isolated process fakes and a temporary loopback HTTP server; never touches a real MGC process."""

import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import quote

import server_panel as panel


class FakeProcesses:
    def __init__(self, project, java, mongo):
        self.project, self.java, self.mongo = project, java, mongo
        self.running = False
        self.mongo_running = True
        self.foreign_http = False
        self.foreign_mongo = False
        self.created = "verified-start-time"
        self.jar_name = "grasscutter-friends-7.1.jar"
        self.starts = []
        self.start_gate = None

    def inspect(self, http_port):
        processes, listeners = [], []
        if self.running:
            processes.append({"pid": 38264, "exe": str(self.java), "created": self.created,
                              "command": f'"{self.java}" -jar "{self.project / self.jar_name}"'})
            listeners.append({"port": http_port, "pid": 38264})
        if self.foreign_http:
            listeners.append({"port": http_port, "pid": 999})
        if self.mongo_running:
            processes.append({"pid": 100, "exe": str(self.mongo), "created": "mongo-start",
                              "command": f'"{self.mongo}" --config "{self.project / "local" / "mongod.cfg"}"'})
            listeners.append({"port": 27017, "pid": 999 if self.foreign_mongo else 100})
        return {"processes": processes, "listeners": listeners}

    def start(self, executable, arguments, project, stdout=None, stderr=None):
        if self.start_gate is not None:
            self.start_gate.wait(3)
        self.starts.append((executable, arguments, project, stdout, stderr))
        if executable == self.mongo:
            self.mongo_running = True
        else:
            self.running = True
            self.jar_name = "grasscutter-7.1.0.jar"
        return object()


class FakeConsole:
    def __init__(self, processes):
        self.processes = processes
        self.metrics = {"retcode": 0, "game": {"players": 0}, "jvm": {"usedMemoryBytes": 123},
                        "threadPools": [{"name": "logic", "active": 0}]}
        self.calls = []
        self.on_login = None

    def status(self, port):
        return self.metrics if self.processes.running else None

    def login(self, port, password):
        self.calls.append(("login", port, password))
        if self.on_login:
            self.on_login()
        return "private-console-session"

    def stop(self, port, token):
        self.calls.append(("stop", port, token))
        self.processes.running = False


class PanelTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.project = self.folder / "MGC project"
        (self.project / "local").mkdir(parents=True)
        (self.project / "logs").mkdir()
        self.java_home, self.mongo_home = self.folder / "JDK 21", self.folder / "MongoDB"
        for path in (self.java_home / "bin" / "java.exe", self.mongo_home / "bin" / "mongod.exe"):
            path.parent.mkdir(parents=True)
            path.touch()
        (self.project / "grasscutter-7.1.0.jar").touch()
        (self.project / "local" / "mongod.cfg").write_text("test-only", encoding="utf-8")
        (self.project / "local" / "environment.json").write_text(json.dumps({
            "javaHome": str(self.java_home), "mongoHome": str(self.mongo_home)}), encoding="utf-8")
        (self.project / "config.json").write_text(json.dumps({"server": {"http": {"bindPort": 8088,
            "webConsole": {"enabled": True, "password": "private-test-password"}}}}), encoding="utf-8")
        self.config = self.folder / "panel.config.json"
        self.config.write_text(json.dumps({"projectRoot": str(self.project), "pythonExe": "existing-python",
                                          "port": 17966}), encoding="utf-8")
        self.processes = FakeProcesses(self.project, self.java_home / "bin" / "java.exe",
                                       self.mongo_home / "bin" / "mongod.exe")
        self.console = FakeConsole(self.processes)
        self.controller = panel.PanelController(self.config, self.processes, self.console,
                                                startup_timeout=.3, stop_timeout=.1)

    def wait_worker(self):
        self.controller.worker.join(3)
        self.assertFalse(self.controller.worker.is_alive())
        self.assertFalse(self.controller.action_lock.locked())

    def test_adopts_verified_existing_friends_jar_and_preserves_complete_metrics(self):
        self.processes.running = True
        # A bogus state-file PID never participates in discovering the real process.
        (self.folder / ".panel-state.json").write_text('{"pid":999,"token":"old"}', encoding="utf-8")
        result = self.controller.status()
        self.assertEqual("running", result["server"]["state"])
        self.assertEqual(38264, result["server"]["pid"])
        self.assertEqual(str(self.project / "grasscutter-friends-7.1.jar"), result["server"]["jar"])
        self.assertEqual(self.console.metrics, result["metrics"])
        self.assertNotIn("private-test-password", json.dumps(result))
        self.assertEqual([], self.processes.starts)

    def test_online_players_and_unknown_count_refuse_stop_before_authentication(self):
        self.processes.running = True
        for count in (2, None, True):
            self.console.metrics["game"]["players"] = count
            with self.assertRaises(panel.PanelError) as rejected:
                self.controller.request_action("restart")
            self.assertEqual(409, rejected.exception.status)
            self.assertEqual([], self.console.calls)
            self.assertFalse(self.controller.action_lock.locked())
        self.console.metrics["game"] = None
        self.assertIsNone(panel.PanelController.online_count(self.console.metrics))

    def test_players_joining_during_login_cancel_stop_and_leave_java_running(self):
        self.processes.running = True
        self.console.on_login = lambda: self.console.metrics["game"].update(players=1)
        code, _ = self.controller.request_action("stop")
        self.assertEqual(202, code)
        self.wait_worker()
        self.assertTrue(self.processes.running)
        self.assertEqual(["login"], [row[0] for row in self.console.calls])
        self.assertIn("1 名玩家在线", self.controller.status()["message"])

    def test_transient_login_failure_keeps_observed_state_and_allows_a_later_stop(self):
        self.processes.running = True
        def unavailable():
            raise OSError("test-only temporary connection failure")
        self.console.on_login = unavailable
        self.assertEqual(202, self.controller.request_action("stop")[0])
        self.wait_worker()
        result = self.controller.status()
        self.assertEqual("running", result["server"]["state"])
        self.assertIn("操作未完成", result["message"])
        self.assertTrue(self.processes.running)
        self.console.on_login = None
        self.assertEqual(202, self.controller.request_action("stop")[0])
        self.wait_worker()
        self.assertEqual("stopped", self.controller.status()["server"]["state"])
        self.assertEqual(["login", "login", "stop"], [row[0] for row in self.console.calls])

    def test_empty_server_restarts_gracefully_with_shared_mongo_and_default_jar(self):
        self.processes.running = True
        code, _ = self.controller.request_action("restart")
        self.assertEqual(202, code)
        self.wait_worker()
        self.assertEqual(["login", "stop"], [row[0] for row in self.console.calls])
        self.assertEqual(1, len(self.processes.starts), "the already-running MongoDB must be reused")
        executable, arguments, cwd, stdout, stderr = self.processes.starts[0]
        self.assertEqual(self.java_home / "bin" / "java.exe", executable)
        self.assertEqual(["-jar", str(self.project / "grasscutter-7.1.0.jar")], arguments[-2:])
        self.assertEqual(self.project, cwd)
        self.assertEqual(self.project / "local" / "server-stdout.log", stdout)
        self.assertEqual("running", self.controller.status()["server"]["state"])

    def test_restart_refuses_changed_pid_identity_and_does_not_send_stop(self):
        self.processes.running = True
        self.console.on_login = lambda: setattr(self.processes, "created", "replacement-process")
        self.controller.request_action("restart")
        self.wait_worker()
        self.assertEqual(["login"], [row[0] for row in self.console.calls])
        self.assertEqual([], self.processes.starts)
        self.assertTrue(self.processes.running)
        self.assertIn("进程已变化", self.controller.status()["message"])

    def test_foreign_http_or_mongo_listener_is_never_stopped_or_replaced(self):
        self.processes.foreign_http = True
        with self.assertRaises(panel.PanelError):
            self.controller.request_action("start")
        self.assertEqual("error", self.controller.status()["server"]["state"])
        self.processes.foreign_http = False
        self.processes.foreign_mongo = True
        self.controller.request_action("start")
        self.wait_worker()
        self.assertEqual([], self.processes.starts)
        self.assertIn("其他数据库", self.controller.status()["message"])
        self.assertEqual([], self.console.calls)

    def test_background_actions_are_mutually_exclusive(self):
        self.processes.start_gate = threading.Event()
        self.controller.request_action("start")
        try:
            self.assertEqual("starting", self.controller.status()["server"]["state"])
            with self.assertRaises(panel.PanelError) as rejected:
                self.controller.request_action("start")
            self.assertEqual(409, rejected.exception.status)
        finally:
            self.processes.start_gate.set()
            self.wait_worker()

    def test_tail_limits_and_redacts_credentials_and_session_tokens(self):
        self.controller.console_token = "private-console-session"
        text = "\n".join(str(index) for index in range(300))
        text += "\nprivate-test-password private-console-session " + self.controller.token + "\n"
        (self.project / "logs" / "latest.log").write_text(text, encoding="utf-8")
        lines = self.controller.logs(3)["lines"]
        self.assertEqual(3, len(lines))
        self.assertEqual("298", lines[0])
        self.assertEqual("[已隐藏] [已隐藏] [已隐藏]", lines[-1])
        with self.assertRaises(panel.PanelError):
            self.controller.logs(0)

    def test_health_is_public_but_api_checks_token_host_origin_and_fetch_site(self):
        server = panel.ThreadingHTTPServer(("127.0.0.1", 0), panel.make_handler(self.controller))
        self.controller.port = server.server_port
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            def request(path, *, method="GET", headers=None, body=None):
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                connection.request(method, path, body=body, headers=headers or {})
                response = connection.getresponse()
                code, response_headers, data = response.status, dict(response.getheaders()), json.loads(response.read())
                connection.close()
                self.assertNotIn("Access-Control-Allow-Origin", response_headers)
                return code, data

            self.assertEqual((200, {"app": panel.APP}), request("/healthz"))
            self.assertEqual(403, request("/api/status")[0])
            valid = {"X-Panel-Token": self.controller.token}
            self.assertEqual(200, request("/api/status", headers=valid)[0])
            for extra in ({"Origin": "https://attacker.invalid"}, {"Host": "attacker.invalid"},
                          {"Sec-Fetch-Site": "cross-site"}, {"Origin": "null"}, {"X-Panel-Token": "wrong"}):
                self.assertEqual(403, request("/api/status", headers={**valid, **extra})[0])
            self.assertEqual(403, request("/.panel-state.json")[0])
            self.assertEqual(404, request("/.panel-state.json", headers=valid)[0])
            self.assertEqual(400, request("/api/logs?lines=nope", headers=valid)[0])
            self.assertEqual(400, request("/api/start", method="POST", headers=valid, body='{"unexpected":1}')[0])
            self.processes.running = True
            self.console.metrics["game"]["players"] = 1
            self.assertEqual(409, request("/api/stop", method="POST", headers=valid, body="{}")[0])
            self.assertEqual([], self.console.calls)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_entry_navigates_to_http_ui_without_exposing_its_session_in_served_template(self):
        template = '<!doctype html><title>MGC fixture</title><p>HTTP UI</p>'
        (self.folder / ".panel-ui.template").write_text(template, encoding="utf-8")
        server = panel.ThreadingHTTPServer(("127.0.0.1", 0), panel.make_handler(self.controller))
        self.controller.port = server.server_port
        self.controller.write_entry()
        entry = (self.folder / "MGC服务器面板.html").read_text(encoding="utf-8")
        target = f"http://127.0.0.1:{server.server_port}/#token={self.controller.token}"
        self.assertIn(f"location.replace({json.dumps(target)})", entry)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            connection.request("GET", "/")
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            self.assertEqual(template, response.read().decode())
            connection.close()
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            connection.request("GET", quote("/MGC服务器面板.html"), headers={"X-Panel-Token": self.controller.token})
            response = connection.getresponse()
            self.assertEqual(404, response.status)
            self.assertNotIn(self.controller.token.encode(), response.read())
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_cross_site_document_navigation_can_open_public_page_but_cannot_call_api(self):
        template = '<!doctype html><title>MGC navigation fixture</title>'
        (self.folder / ".panel-ui.template").write_text(template, encoding="utf-8")
        server = panel.ThreadingHTTPServer(("127.0.0.1", 0), panel.make_handler(self.controller))
        self.controller.port = server.server_port
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        navigation = {"Sec-Fetch-Site": "cross-site", "Sec-Fetch-Mode": "navigate",
                      "Sec-Fetch-Dest": "document"}
        try:
            def request(path, headers, method="GET"):
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
                connection.request(method, path, body="{}" if method == "POST" else None, headers=headers)
                response = connection.getresponse()
                code, body = response.status, response.read().decode()
                self.assertIsNone(response.getheader("Access-Control-Allow-Origin"))
                connection.close()
                return code, body

            self.assertEqual((200, template), request("/", navigation))
            for invalid in ({"Host": "attacker.invalid"}, {"Origin": "null"},
                            {"Origin": "https://attacker.invalid"}, {"Sec-Fetch-Mode": "cors"},
                            {"Sec-Fetch-Dest": "iframe"}):
                self.assertEqual(403, request("/", {**navigation, **invalid})[0])
            with_token = {**navigation, "X-Panel-Token": self.controller.token}
            self.assertEqual(403, request("/api/status", with_token)[0])
            self.assertEqual(403, request("/api/stop", with_token, "POST")[0])
            self.assertEqual([], self.console.calls)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_java_console_uses_existing_protocol_and_never_follows_login_redirects(self):
        calls = []
        redirect = [False]

        class JavaHandler(panel.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                body = json.dumps({"retcode": 0, "game": {"players": 0}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append((self.path, body, self.headers.get("X-Console-Token")))
                if redirect[0]:
                    self.send_response(302)
                    self.send_header("Location", "/must-not-receive-credentials")
                    self.end_headers()
                    return
                response = {"token": "console-test-token"} if self.path.endswith("login") else {"output": []}
                data = json.dumps(response).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        server = panel.ThreadingHTTPServer(("127.0.0.1", 0), JavaHandler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            console = panel.JavaConsole()
            self.assertEqual(0, console.status(server.server_port)["game"]["players"])
            token = console.login(server.server_port, "fixture-password")
            console.stop(server.server_port, token)
            self.assertEqual(("/console/api/login", {"password": "fixture-password"}, None), calls[0])
            self.assertEqual(("/console/api/command", {"command": "stop"}, token), calls[1])
            redirect[0] = True
            with self.assertRaises(panel.PanelError) as rejected:
                console.login(server.server_port, "fixture-password")
            self.assertNotIn("fixture-password", str(rejected.exception))
            self.assertEqual(3, len(calls), "redirect destinations must never receive credentials")
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)


if __name__ == "__main__":
    unittest.main()
