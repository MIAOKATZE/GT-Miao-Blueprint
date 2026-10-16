"""Version-2 CutNativeClientProbe input bridge, restricted to one fixture Java PID.

Run from the repository with --fixture DIR --receipts DIR --pid PID --timeout SECONDS.
Mouse/key messages address only the verified LWJGL/GLFW/SDL HWND. SendInput supplies
the asynchronous state of modifiers while that HWND is foreground; cleanup releases
only inputs owned by this bridge. Posted messages are not physical pointer evidence.
Requires Windows and psutil. This script never starts/stops Java or closes a window.
"""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import re
import sys
import time
import uuid

import psutil


ROOT = Path(__file__).resolve().parents[4]
METHOD = "target HWND PostMessage; foreground modifier state via SendInput"
KEYS = {16, 17, 18, 27, 65, 68, 82}  # The probe sends AWT virtual-key codes.
MODIFIERS = {16, 17, 18}
OPERATIONS = {"focus", "key-down", "key-up", "mouse-move", "mouse-down", "mouse-up", "release-all"}


def within(path: Path, parent: Path) -> Path:
    resolved = path.resolve(strict=True)
    resolved.relative_to(parent)
    if not resolved.is_dir():
        raise ValueError(f"Expected directory: {resolved}")
    return resolved


def read_request(path: Path, sequence: int, pid: int) -> dict:
    if path.is_symlink() or not path.is_file() or path.resolve().parent != path.parent.resolve():
        raise ValueError("Request must be a regular file directly inside receipts")
    if path.stat().st_size > 4096:
        raise ValueError("Oversized request")
    values = {}
    for line in path.read_text(encoding="ascii").splitlines():
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise ValueError("Malformed or duplicate request property")
        values[key] = value
    expected = {"version", "sequence", "pid", "operation", "key", "x", "y", "width", "height"}
    if set(values) != expected:
        raise ValueError("Unexpected request property set")
    result = {key: int(value) if key != "operation" else value for key, value in values.items()}
    if result["version"] != 2 or result["sequence"] != sequence or result["pid"] != pid:
        raise ValueError("Request version, sequence or target PID does not match")
    if result["operation"] not in OPERATIONS:
        raise ValueError("Unsupported input operation")
    if not (1 <= result["width"] <= 32767 and 1 <= result["height"] <= 32767):
        raise ValueError("Invalid client dimensions")
    if result["operation"].startswith("key-") and result["key"] not in KEYS:
        raise ValueError("Key is outside the probe's explicit virtual-key allowlist")
    if result["operation"].startswith("mouse-") and result["key"] not in (0, 1):
        raise ValueError("Only left/right mouse buttons are supported")
    if result["operation"] in ("focus", "mouse-move", "mouse-down") and not (
        0 <= result["x"] < result["width"] and 0 <= result["y"] < result["height"]
    ):
        raise ValueError("Pointer is outside the requested client area")
    return result


def property_value(value) -> str:
    value = str(value).replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    # Properties.load(InputStream) reads Latin-1 and Java unicode escapes.
    return "".join(c if ord(c) < 128 else "".join(f"\\u{u:04x}" for u in
                   [int.from_bytes(c.encode("utf-16-be")[i:i + 2], "big")
                    for i in range(0, len(c.encode("utf-16-be")), 2)]) for c in value)


def acknowledge(receipts: Path, sequence: int, values: dict) -> None:
    target = receipts / f"cut-os-input-ack-{sequence}.properties"
    temporary = receipts / f"cut-os-input-ack-{sequence}.{os.getpid()}.writing"
    if target.exists():
        raise FileExistsError(f"Never overwrite an existing ACK: {target.name}")
    try:
        with temporary.open("x", encoding="ascii", newline="\n") as stream:
            for key, value in {"version": 2, "sequence": sequence, **values}.items():
                stream.write(f"{key}={property_value(value)}\n")
            stream.flush()
            os.fsync(stream.fileno())
        # On Windows rename is atomic and refuses an existing destination.
        os.rename(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


class Target:
    def __init__(self, pid: int, fixture: Path):
        self.pid, self.fixture = pid, fixture
        self.process = psutil.Process(pid)
        self.created = self.process.create_time()
        self.validate()

    def validate(self):
        process = psutil.Process(self.pid)
        if process.create_time() != self.created or not process.is_running():
            raise RuntimeError("Target Java PID exited or was reused")
        if Path(process.exe()).name.lower() not in ("java.exe", "javaw.exe"):
            raise RuntimeError("Target executable is not java.exe/javaw.exe")
        fixture = str(self.fixture).replace("\\", "/").casefold().rstrip("/")
        pattern = re.compile(r"(?:^|[=@])" + re.escape(fixture) + r"(?:/|$)")
        if not any(pattern.search(arg.strip('"').replace("\\", "/").casefold())
                   for arg in process.cmdline()):
            raise RuntimeError("Java command line does not contain the explicit fixture path")


class Win32:
    def __init__(self, target: Target):
        self.target = target
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.hwnd = None
        self.keys, self.physical, self.buttons = set(), set(), {}
        u = self.user
        self.callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        signatures = {
            "EnumWindows": ([self.callback_type, wintypes.LPARAM], wintypes.BOOL),
            "GetWindowThreadProcessId": ([wintypes.HWND, ctypes.POINTER(wintypes.DWORD)], wintypes.DWORD),
            "IsWindow": ([wintypes.HWND], wintypes.BOOL),
            "IsWindowVisible": ([wintypes.HWND], wintypes.BOOL),
            "GetClassNameW": ([wintypes.HWND, wintypes.LPWSTR, ctypes.c_int], ctypes.c_int),
            "GetClientRect": ([wintypes.HWND, ctypes.POINTER(wintypes.RECT)], wintypes.BOOL),
            "GetForegroundWindow": ([], wintypes.HWND),
            "SetForegroundWindow": ([wintypes.HWND], wintypes.BOOL),
            "PostMessageW": ([wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM], wintypes.BOOL),
            "MapVirtualKeyW": ([wintypes.UINT, wintypes.UINT], wintypes.UINT),
            "GetAsyncKeyState": ([ctypes.c_int], ctypes.c_short),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(u, name)
            function.argtypes, function.restype = arguments, result
        # Match Minecraft display pixels instead of DPI-virtualized desktop coordinates.
        u.SetProcessDPIAware()

        class KeyboardInput(ctypes.Structure):
            _fields_ = [("vk", wintypes.WORD), ("scan", wintypes.WORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("extra", ctypes.c_size_t)]

        class MouseInput(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                        ("data", wintypes.DWORD), ("flags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]

        class InputUnion(ctypes.Union):
            _fields_ = [("keyboard", KeyboardInput), ("mouse", MouseInput)]

        class Input(ctypes.Structure):
            _fields_ = [("type", wintypes.DWORD), ("value", InputUnion)]

        self.Input, self.KeyboardInput = Input, KeyboardInput
        if ctypes.sizeof(Input) != (40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28):
            raise RuntimeError("Unexpected Win32 INPUT structure layout")
        u.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(Input), ctypes.c_int]
        u.SendInput.restype = wintypes.UINT

    def owned(self, hwnd):
        pid = wintypes.DWORD()
        return bool(hwnd and self.user.IsWindow(hwnd)
                    and self.user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                    and pid.value == self.target.pid)

    def bind(self, request):
        self.target.validate()
        found = []

        @self.callback_type
        def visit(hwnd, _):
            if self.owned(hwnd) and self.user.IsWindowVisible(hwnd):
                name = ctypes.create_unicode_buffer(256)
                self.user.GetClassNameW(hwnd, name, len(name))
                if "lwjgl" in name.value.lower() or "glfw" in name.value.lower() or name.value == "SDL_app":
                    found.append((hwnd, name.value))
            return True

        if not self.user.EnumWindows(visit, 0):
            raise ctypes.WinError(ctypes.get_last_error())
        if len(found) != 1:
            raise RuntimeError(f"Expected one visible LWJGL/GLFW/SDL window for PID; found {len(found)}")
        hwnd, window_class = found[0]
        if self.hwnd and hwnd != self.hwnd:
            raise RuntimeError("Target HWND changed during this bridge session")
        self.hwnd = hwnd
        rect = wintypes.RECT()
        if not self.user.GetClientRect(hwnd, ctypes.byref(rect)):
            raise ctypes.WinError(ctypes.get_last_error())
        size = (rect.right - rect.left, rect.bottom - rect.top)
        if size != (request["width"], request["height"]):
            raise RuntimeError(f"Client pixel dimensions {size} differ from request")
        return {"pid": self.target.pid, "hwnd": hex(hwnd), "window_class": window_class,
                "client_width": size[0], "client_height": size[1]}

    def foreground(self, focus=False):
        if focus:
            self.user.SetForegroundWindow(self.hwnd)
            limit = time.monotonic() + 1
            while self.user.GetForegroundWindow() != self.hwnd and time.monotonic() < limit:
                time.sleep(.02)
        if self.user.GetForegroundWindow() != self.hwnd:
            raise RuntimeError("Target HWND is not foreground; refusing input")

    def post(self, message, word=0, parameter=0):
        self.target.validate()
        if not self.owned(self.hwnd):
            raise RuntimeError("Target HWND no longer belongs to the bound Java PID")
        if not self.user.PostMessageW(self.hwnd, message, word, parameter):
            raise ctypes.WinError(ctypes.get_last_error())

    def send_modifier(self, key, down):
        if down:
            self.target.validate()
            self.foreground()
        event = self.Input()
        event.type = 1  # INPUT_KEYBOARD; never inject global mouse input.
        event.value.keyboard = self.KeyboardInput(key, 0, 0 if down else 2, 0, 0)
        if self.user.SendInput(1, ctypes.byref(event), ctypes.sizeof(event)) != 1:
            raise ctypes.WinError(ctypes.get_last_error())

    def keyboard(self, key, down):
        if down and key in self.keys:
            raise ValueError("Duplicate key-down")
        if not down and key not in self.keys:
            raise ValueError("Cannot release a key this bridge did not press")
        if down and self.user.GetAsyncKeyState(key) & 0x8000:
            raise RuntimeError("Key is already physically held; refusing to take ownership")
        scan = self.user.MapVirtualKeyW(key, 0)
        alt = key == 18 or 18 in self.keys
        parameter = 1 | (scan << 16) | ((1 << 29) if alt else 0)
        if not down:
            parameter |= (1 << 30) | (1 << 31)
        if key in MODIFIERS:
            self.send_modifier(key, down)
            if down:
                self.physical.add(key)
            else:
                self.physical.discard(key)
        self.post((0x104 if down else 0x105) if alt else (0x100 if down else 0x101), key, parameter)
        if down:
            self.keys.add(key)
        else:
            self.keys.remove(key)

    def mouse_move(self, x, y):
        parameter = (x & 0xffff) | ((y & 0xffff) << 16)
        mask = sum(1 if b == 0 else 2 for b in self.buttons)
        mask |= 4 if 16 in self.keys else 0
        mask |= 8 if 17 in self.keys else 0
        self.post(0x200, mask, parameter)

    def mouse(self, button, down, x=0, y=0):
        if down:
            if button in self.buttons:
                raise ValueError("Duplicate mouse-down")
        elif button not in self.buttons:
            raise ValueError("Cannot release a mouse button this bridge did not press")
        if not down:
            x, y = self.buttons[button]
        parameter = (x & 0xffff) | ((y & 0xffff) << 16)
        mask = sum(1 if b == 0 else 2 for b in self.buttons)
        mask |= 4 if 16 in self.keys else 0
        mask |= 8 if 17 in self.keys else 0
        self.mouse_move(x, y)  # WM_MOUSEMOVE in verified target-client coordinates.
        if down:
            mask |= 1 if button == 0 else 2
        else:
            mask &= ~(1 if button == 0 else 2)
        self.post((0x201 if down else 0x202) if button == 0 else (0x204 if down else 0x205), mask, parameter)
        if down:
            self.buttons[button] = (x, y)
        else:
            del self.buttons[button]

    def release(self):
        errors = []
        for button in list(self.buttons):
            try:
                self.mouse(button, False)
            except Exception as error:
                errors.append(str(error))
        for key in sorted(self.keys - MODIFIERS) + sorted(self.keys & MODIFIERS):
            try:
                self.keyboard(key, False)
            except Exception as error:
                errors.append(str(error))
        # A terminated Java process must not leave a modifier injected by us held globally.
        for key in list(self.physical):
            try:
                self.send_modifier(key, False)
                self.physical.remove(key)
            except Exception as error:
                errors.append(str(error))
        if errors:
            raise RuntimeError("Input release incomplete: " + "; ".join(errors))

    def execute(self, request):
        evidence = self.bind(request)
        operation = request["operation"]
        if operation == "release-all":
            self.release()
        else:
            self.foreground(focus=operation == "focus")
            if operation.startswith("key-"):
                self.keyboard(request["key"], operation == "key-down")
            elif operation == "mouse-move":
                self.mouse_move(request["x"], request["y"])
            elif operation.startswith("mouse-"):
                self.mouse(request["key"], operation == "mouse-down", request["x"], request["y"])
        return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--receipts", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=900)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("Win32 input requires Windows")
    if args.pid <= 0 or args.timeout <= 0:
        parser.error("PID and timeout must be positive")
    fixture = within(args.fixture, ROOT)
    receipts = within(args.receipts, fixture)
    bridge = Win32(Target(args.pid, fixture))
    lock = receipts / "cut-os-input-bridge.lock"
    token = f"bridge_pid={os.getpid()}\ntarget_pid={args.pid}\ntoken={uuid.uuid4()}\n"
    with lock.open("x", encoding="ascii") as stream:
        stream.write(token)
    sequence, deadline = 1, time.monotonic() + args.timeout
    try:
        while time.monotonic() < deadline:
            if (receipts / "cut-os-input-bridge-stop.txt").exists():
                raise RuntimeError("Owned paired launcher requested input release and shutdown")
            request_path = receipts / f"cut-os-input-request-{sequence}.properties"
            ack_path = receipts / f"cut-os-input-ack-{sequence}.properties"
            if ack_path.exists():
                # Never replay a previously acknowledged request or replace its evidence.
                sequence += 1
                continue
            if request_path.exists():
                try:
                    request = read_request(request_path, sequence, args.pid)
                    evidence = bridge.execute(request)
                    acknowledge(receipts, sequence, {"success": "true", "input_method": METHOD,
                                                      "failure": "", **evidence})
                    print(f"ACK {sequence} {request['operation']}", flush=True)
                except Exception as error:
                    acknowledge(receipts, sequence, {"success": "false", "input_method": METHOD,
                                                      "failure": str(error)})
                    raise
                sequence += 1
                continue
            if (receipts / "cut-client-fail.txt").exists():
                raise RuntimeError("Native client reported failure")
            if (receipts / "cut-client-pass.txt").exists():
                return 0
            bridge.target.validate()
            time.sleep(.02)
        raise TimeoutError("Timed out waiting for the native input protocol")
    finally:
        try:
            bridge.release()
        finally:
            if lock.read_text(encoding="ascii") == token:
                lock.unlink()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"native_input: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
