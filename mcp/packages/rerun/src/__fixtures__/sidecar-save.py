"""Exercise either sidecar's CLI/save protocol without a Rerun installation.

Only the renderer and TCP transport are stubbed. The real main loop consumes
JSON-lines, handles save_now/disconnect, calls rr.save and emits its reply.
"""
import json
import runpy
import socket
import sys
import types

script, save_path, mode = sys.argv[1:]
rerun = types.ModuleType("rerun")
rerun.init = lambda *args, **kwargs: None
rerun.log = lambda *args, **kwargs: None
rerun.set_time_nanos = lambda *args: None
rerun.save = lambda path, **kwargs: print("SDK_SAVE=" + path)
rerun.ViewCoordinates = types.SimpleNamespace(RIGHT_HAND_Y_UP=None)
sys.modules["rerun"] = rerun
sys.modules["numpy"] = types.ModuleType("numpy")


class Connection:
    def __init__(self):
        events = [{"type": "scalar", "t": 1, "value": 42}]
        if mode == "explicit":
            events.append({"type": "control", "cmd": "save_now"})
        self.data = ("\n".join(json.dumps(event) for event in events) + "\n").encode()

    def recv(self, size):
        data, self.data = self.data, b""
        return data

    def sendall(self, data):
        print("ACK=" + data.decode().strip())

    def close(self):
        pass


class Server:
    def __init__(self, *args):
        self.accepted = False

    def setsockopt(self, *args):
        pass

    def bind(self, *args):
        pass

    def listen(self, *args):
        pass

    def accept(self):
        if self.accepted:
            raise KeyboardInterrupt
        self.accepted = True
        return Connection(), ("127.0.0.1", 1234)

    def close(self):
        pass


socket.socket = Server
sys.argv = [script, "--save", save_path]
runpy.run_path(script, run_name="__main__")
