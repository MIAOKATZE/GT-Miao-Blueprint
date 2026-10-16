"""Version-2 CutNativeClientProbe input bridge, restricted to one fixture Java PID.

Run from the repository with --fixture DIR --receipts DIR --pid PID --timeout SECONDS.
Mouse SendInput requires the verified LWJGL/GLFW/SDL HWND to be foreground and
the physical hit window to have its PID. Keys use targeted messages and SendInput
modifier state. Cleanup releases only inputs owned by this bridge.
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
METHOD = "target HWND verified SendInput mouse; PostMessage keys + SendInput modifiers"
KEYS = {16, 17, 18, 27, 65, 68, 82}  # The probe sends AWT virtual-key codes.
MODIFIERS = {16, 17, 18}
OPERATIONS = {"focus", "key-down", "key-up", "mouse-move", "mouse-down", "mouse-up", "release-all"}


class WindowPlacement(ctypes.Structure):
    # WinUser.h: rcDevice is _MAC-only and is not part of the Windows ABI.
    _fields_ = [("length", wintypes.UINT), ("flags", wintypes.UINT), ("showCmd", wintypes.UINT),
                ("ptMinPosition", wintypes.POINT), ("ptMaxPosition", wintypes.POINT),
                ("rcNormalPosition", wintypes.RECT)]


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
        self.bind_evidence = {}
        self.focus_evidence = {}
        self.mouse_evidence = {}
        self.pending_pointer = None
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
            "GetWindowPlacement": ([wintypes.HWND, ctypes.POINTER(WindowPlacement)], wintypes.BOOL),
            "ClientToScreen": ([wintypes.HWND, ctypes.POINTER(wintypes.POINT)], wintypes.BOOL),
            "GetPhysicalCursorPos": ([ctypes.POINTER(wintypes.POINT)], wintypes.BOOL),
            "WindowFromPhysicalPoint": ([wintypes.POINT], wintypes.HWND),
            "GetSystemMetrics": ([ctypes.c_int], ctypes.c_int),
            "SetThreadDpiAwarenessContext": ([ctypes.c_void_p], ctypes.c_void_p),
            "GetForegroundWindow": ([], wintypes.HWND),
            "SetForegroundWindow": ([wintypes.HWND], wintypes.BOOL),
            "ShowWindow": ([wintypes.HWND, ctypes.c_int], wintypes.BOOL),
            "IsIconic": ([wintypes.HWND], wintypes.BOOL),
            "BringWindowToTop": ([wintypes.HWND], wintypes.BOOL),
            "SetActiveWindow": ([wintypes.HWND], wintypes.HWND),
            "SetFocus": ([wintypes.HWND], wintypes.HWND),
            "AttachThreadInput": ([wintypes.DWORD, wintypes.DWORD, wintypes.BOOL], wintypes.BOOL),
            "GetThreadDesktop": ([wintypes.DWORD], wintypes.HANDLE),
            "GetUserObjectInformationW": ([wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                             wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)], wintypes.BOOL),
            "PeekMessageW": ([ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT,
                               wintypes.UINT, wintypes.UINT], wintypes.BOOL),
            "PostMessageW": ([wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM], wintypes.BOOL),
            "MapVirtualKeyW": ([wintypes.UINT, wintypes.UINT], wintypes.UINT),
            "GetAsyncKeyState": ([ctypes.c_int], ctypes.c_short),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(u, name)
            function.argtypes, function.restype = arguments, result
        if ctypes.sizeof(wintypes.RECT) != 16 or ctypes.sizeof(WindowPlacement) != 44:
            raise RuntimeError("Unexpected Win32 RECT/WINDOWPLACEMENT ABI layout")
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.GetCurrentThreadId.argtypes = []
        self.kernel.GetCurrentThreadId.restype = wintypes.DWORD
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

        self.Input, self.KeyboardInput, self.MouseInput = Input, KeyboardInput, MouseInput
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
        self.bind_evidence = {}
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
        self.bind_evidence.update(pid=self.target.pid, hwnd=hex(hwnd), window_class=window_class,
                                  bind_expected_width=request["width"], bind_expected_height=request["height"],
                                  bind_restore_requested="false")

        def client_size(prefix):
            rect = wintypes.RECT()
            if not self.user.GetClientRect(hwnd, ctypes.byref(rect)):
                self.bind_evidence[prefix + "_getclientrect_error"] = ctypes.get_last_error()
                raise ctypes.WinError(ctypes.get_last_error())
            size = (rect.right - rect.left, rect.bottom - rect.top)
            self.bind_evidence.update({prefix + "_width": size[0], prefix + "_height": size[1]})
            return size

        def placement(prefix):
            iconic = bool(self.user.IsIconic(hwnd))
            self.bind_evidence[prefix + "_iconic"] = str(iconic).lower()
            state = WindowPlacement()
            state.length = ctypes.sizeof(state)
            if not self.user.GetWindowPlacement(hwnd, ctypes.byref(state)):
                self.bind_evidence[prefix + "_placement_error"] = ctypes.get_last_error()
                raise ctypes.WinError(ctypes.get_last_error())
            normal = state.rcNormalPosition
            self.bind_evidence.update({prefix + "_show_cmd": state.showCmd,
                                      prefix + "_placement_flags": state.flags,
                                      prefix + "_normal_rect": f"{normal.left},{normal.top},{normal.right},{normal.bottom}"})
            return iconic, state.showCmd

        initial_iconic, initial_show = placement("bind_initial") if request["operation"] == "focus" else (False, 0)
        size = client_size("bind_initial_client")
        expected = (request["width"], request["height"])
        if request["operation"] == "focus" and (initial_iconic or initial_show in (2, 6, 7, 11) or 0 in size):
            started = time.monotonic()
            try:
                self.target.validate()
                if not self.owned(hwnd):
                    raise RuntimeError("Owned HWND changed before focus-bind restore")
                self.bind_evidence["bind_restore_requested"] = "true"
                self.bind_evidence["bind_restore_show_cmd"] = 9  # SW_RESTORE, verified owned HWND only.
                self.bind_evidence["bind_restore_return_was_visible"] = str(bool(self.user.ShowWindow(hwnd, 9))).lower()
                while True:
                    self.target.validate()
                    if not self.owned(hwnd) or not self.user.IsWindowVisible(hwnd):
                        raise RuntimeError("Owned visible HWND changed while awaiting restored client area")
                    stop_file = getattr(self, "stop_file", None)
                    if stop_file is not None and stop_file.exists():
                        raise RuntimeError("Owned launcher requested shutdown while awaiting restored client area")
                    size = client_size("bind_restored_client")
                    if size == expected or time.monotonic() - started >= 1:
                        break
                    time.sleep(.02)
            finally:
                self.bind_evidence["bind_restore_wait_ms"] = round((time.monotonic() - started) * 1000)
                self.bind_evidence.update(bind_final_client_width=size[0], bind_final_client_height=size[1])
        if request["operation"] == "focus":
            placement("bind_final")
        self.bind_evidence.update(bind_final_client_width=size[0], bind_final_client_height=size[1])
        if size != expected:
            raise RuntimeError(f"Client pixel dimensions {size} differ from request")
        self.request_width, self.request_height = request["width"], request["height"]
        return {"pid": self.target.pid, "hwnd": hex(hwnd), "window_class": window_class,
                "client_width": size[0], "client_height": size[1]}

    def foreground(self, focus=False):
        def identity(hwnd):
            pid = wintypes.DWORD()
            if hwnd:
                self.user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            return hex(hwnd or 0), pid.value

        if focus:
            focus_started = time.monotonic()
            focus_deadline = focus_started + 12
            before = identity(self.user.GetForegroundWindow())
            self.focus_evidence = {"focus_before_hwnd": before[0], "focus_before_pid": before[1],
                                   "focus_method": "owned HWND show/raise; bounded same-desktop queue attachment",
                                   "focus_attach": "not needed"}
            try:
                self.target.validate()
                if not self.owned(self.hwnd):
                    raise RuntimeError("Focus target no longer belongs to the bound Java PID")
                # AttachThreadInput resets queue key state. Focus-only attachment is
                # permitted solely with no physical input and no bridge-owned holds.
                all_observed_held = set()

                def check_focus_target():
                    self.target.validate()
                    if not self.owned(self.hwnd):
                        raise RuntimeError("Owned focus target changed while awaiting focus")
                    stop_file = getattr(self, "stop_file", None)
                    if stop_file is not None and stop_file.exists():
                        raise RuntimeError("Owned paired launcher requested shutdown while awaiting focus")
                    if time.monotonic() >= focus_deadline:
                        raise RuntimeError("Shared 12-second focus deadline expired; refusing focus queue changes")

                def require_idle_input():
                    held = [key for key in range(1, 256) if self.user.GetAsyncKeyState(key) & 0x8000]
                    if self.keys or self.physical or self.buttons or held:
                        self.focus_evidence["focus_race_held_vks"] = ",".join(map(str, held))
                        raise RuntimeError("Input is held; refusing focus queue changes")

                def wait_idle(stage):
                    # Called only before attachment. No input is released or synthesized.
                    idle_started = time.monotonic()
                    idle_since = None
                    observed_held = set()
                    prefix = "focus_" + stage + "_"
                    try:
                        while True:
                            check_focus_target()
                            if self.keys or self.physical or self.buttons:
                                raise RuntimeError("Bridge-owned input is held; refusing focus queue changes")
                            held = [key for key in range(1, 256) if self.user.GetAsyncKeyState(key) & 0x8000]
                            observed_held.update(held)
                            all_observed_held.update(held)
                            self.focus_evidence[prefix + "held_vks"] = ",".join(map(str, sorted(observed_held)))
                            self.focus_evidence[prefix + "last_held_vks"] = ",".join(map(str, held))
                            self.focus_evidence["focus_held_vks"] = ",".join(map(str, sorted(all_observed_held)))
                            self.focus_evidence["focus_last_held_vks"] = ",".join(map(str, held))
                            now = time.monotonic()
                            if held:
                                idle_since = None
                            elif idle_since is None:
                                idle_since = now
                            elif now - idle_since >= .75:
                                break
                            time.sleep(.02)
                    finally:
                        waited = round((time.monotonic() - idle_started) * 1000)
                        self.focus_evidence[prefix + "idle_wait_ms"] = waited
                        self.focus_evidence["focus_idle_wait_ms"] = self.focus_evidence.get("focus_idle_wait_ms", 0) + waited
                        observed_hwnd = self.user.GetForegroundWindow()
                        observed_pid = wintypes.DWORD()
                        observed_thread = self.user.GetWindowThreadProcessId(
                            observed_hwnd, ctypes.byref(observed_pid)) if observed_hwnd else 0
                        self.focus_evidence.update({prefix + "after_idle_hwnd": hex(observed_hwnd or 0),
                                                    prefix + "after_idle_pid": observed_pid.value,
                                                    prefix + "after_idle_thread": observed_thread})

                wait_idle("initial")
                require_idle_input()
                self.user.ShowWindow(self.hwnd, 9 if self.user.IsIconic(self.hwnd) else 5)
                self.user.BringWindowToTop(self.hwnd)
                self.user.SetForegroundWindow(self.hwnd)
                limit = min(focus_deadline, time.monotonic() + .3)
                while self.user.GetForegroundWindow() != self.hwnd and time.monotonic() < limit:
                    check_focus_target()
                    time.sleep(.02)
                if self.user.GetForegroundWindow() != self.hwnd:
                    current_thread = self.kernel.GetCurrentThreadId()
                    target_pid = wintypes.DWORD()
                    target_thread = self.user.GetWindowThreadProcessId(self.hwnd, ctypes.byref(target_pid))
                    if not target_thread or target_pid.value != self.target.pid:
                        raise RuntimeError("Owned target thread changed before focus attachment")

                    def desktop_name(thread):
                        handle = self.user.GetThreadDesktop(thread)
                        name = ctypes.create_unicode_buffer(256)
                        needed = wintypes.DWORD()
                        if not handle or not self.user.GetUserObjectInformationW(
                            handle, 2, name, ctypes.sizeof(name), ctypes.byref(needed)
                        ):
                            raise ctypes.WinError(ctypes.get_last_error())
                        return name.value

                    if desktop_name(current_thread) != desktop_name(target_thread):
                        raise RuntimeError("Owned target thread is on another desktop")
                    # Explicitly create this bridge thread's message queue, without consuming messages.
                    message = wintypes.MSG()
                    self.user.PeekMessageW(ctypes.byref(message), None, 0, 0, 0)
                    wait_idle("owned_target_attach")
                    after_idle = identity(self.user.GetForegroundWindow())
                    self.focus_evidence.update(focus_owned_target_after_idle_hwnd=after_idle[0],
                                               focus_owned_target_after_idle_pid=after_idle[1])
                    attached = False
                    try:
                        if current_thread != target_thread:
                            require_idle_input()
                            self.focus_evidence["focus_attach"] = "attempted bridge-to-owned-target attachment"
                            if not self.user.AttachThreadInput(current_thread, target_thread, True):
                                raise ctypes.WinError(ctypes.get_last_error())
                            attached = True
                            self.focus_evidence["focus_attach"] = "bridge thread to verified target thread"
                        self.user.BringWindowToTop(self.hwnd)
                        self.user.SetActiveWindow(self.hwnd)
                        self.user.SetFocus(self.hwnd)
                        self.user.SetForegroundWindow(self.hwnd)
                    finally:
                        if attached:
                            detached = self.user.AttachThreadInput(current_thread, target_thread, False)
                            self.focus_evidence["focus_detached"] = str(bool(detached)).lower()
                            if not detached:
                                raise ctypes.WinError(ctypes.get_last_error())
                    # If the caller still lacks foreground permission, temporarily join
                    # the current foreground queue. Never activate/message its HWND.
                    # A changing foreground handle permits only one fresh observation.
                    for attempt in range(1, 3):
                        if self.user.GetForegroundWindow() == self.hwnd:
                            break
                        prefix = f"focus_foreground_attempt_{attempt}_"
                        before_idle = self.user.GetForegroundWindow()
                        self.focus_evidence[prefix + "before_idle_hwnd"] = identity(before_idle)[0]
                        self.focus_evidence[prefix + "before_idle_pid"] = identity(before_idle)[1]
                        wait_idle(f"foreground_attempt_{attempt}_attach")
                        foreground_hwnd = self.user.GetForegroundWindow()
                        foreground_pid = wintypes.DWORD()
                        foreground_thread = self.user.GetWindowThreadProcessId(
                            foreground_hwnd, ctypes.byref(foreground_pid)) if foreground_hwnd else 0
                        self.focus_evidence.update({prefix + "hwnd": hex(foreground_hwnd or 0),
                                                    prefix + "pid": foreground_pid.value,
                                                    prefix + "thread": foreground_thread,
                                                    prefix + "attach": "not attempted"})
                        if foreground_hwnd == self.hwnd:
                            break
                        if foreground_hwnd != before_idle:
                            self.focus_evidence[prefix + "changed"] = "during idle wait; attachment skipped"
                            continue
                        if not foreground_thread:
                            raise RuntimeError("No stable foreground thread for focus transfer")
                        require_idle_input()
                        if not (desktop_name(current_thread) == desktop_name(target_thread)
                                == desktop_name(foreground_thread)):
                            raise RuntimeError("Foreground and owned target threads are not on the same desktop")
                        self.focus_evidence[prefix + "same_desktop"] = "true"
                        if self.user.GetForegroundWindow() != foreground_hwnd:
                            self.focus_evidence[prefix + "changed"] = "before attachment"
                            continue
                        joined = False
                        changed = False
                        try:
                            self.target.validate()
                            if not self.owned(self.hwnd):
                                raise RuntimeError("Owned focus target changed before foreground attachment")
                            require_idle_input()
                            if foreground_thread != current_thread:
                                self.focus_evidence[prefix + "attach"] = "attempted"
                                if not self.user.AttachThreadInput(current_thread, foreground_thread, True):
                                    raise ctypes.WinError(ctypes.get_last_error())
                                joined = True
                                self.focus_evidence[prefix + "attach"] = "true"
                            else:
                                self.focus_evidence[prefix + "attach"] = "already this bridge thread"
                            if self.user.GetForegroundWindow() != foreground_hwnd:
                                changed = True
                                self.focus_evidence[prefix + "changed"] = "after attachment; activation skipped"
                            else:
                                self.target.validate()
                                if not self.owned(self.hwnd):
                                    raise RuntimeError("Owned focus target changed during foreground attachment")
                                require_idle_input()
                                if self.user.GetForegroundWindow() != foreground_hwnd:
                                    changed = True
                                    self.focus_evidence[prefix + "changed"] = "before activation; activation skipped"
                                else:
                                    self.focus_evidence[prefix + "set_foreground_return"] = str(bool(
                                        self.user.SetForegroundWindow(self.hwnd))).lower()
                                    self.user.BringWindowToTop(self.hwnd)
                        finally:
                            if joined:
                                detached = self.user.AttachThreadInput(current_thread, foreground_thread, False)
                                self.focus_evidence[prefix + "detached"] = str(bool(detached)).lower()
                                if not detached:
                                    raise ctypes.WinError(ctypes.get_last_error())
                            current = identity(self.user.GetForegroundWindow())
                            self.focus_evidence.update({prefix + "after_hwnd": current[0],
                                                        prefix + "after_pid": current[1]})
                        if changed:
                            continue
                        # Foreground activation may finish asynchronously after queues detach.
                        transfer_limit = min(focus_deadline, time.monotonic() + .5)
                        while self.user.GetForegroundWindow() != self.hwnd and time.monotonic() < transfer_limit:
                            check_focus_target()
                            time.sleep(.02)
                        current = identity(self.user.GetForegroundWindow())
                        self.focus_evidence.update({prefix + "settled_hwnd": current[0],
                                                    prefix + "settled_pid": current[1]})
                        # Retry solely for a changed foreground, not repeated permission forcing.
                        if self.user.GetForegroundWindow() in (foreground_hwnd, self.hwnd):
                            break
                limit = min(focus_deadline, time.monotonic() + 1)
                while self.user.GetForegroundWindow() != self.hwnd and time.monotonic() < limit:
                    check_focus_target()
                    time.sleep(.02)
                self.target.validate()
            finally:
                after = identity(self.user.GetForegroundWindow())
                self.focus_evidence.update(focus_after_hwnd=after[0], focus_after_pid=after[1])
                self.focus_evidence["focus_total_ms"] = round((time.monotonic() - focus_started) * 1000)
                self.focus_evidence["focus_verified"] = str(
                    self.user.GetForegroundWindow() == self.hwnd and self.owned(self.hwnd)).lower()
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
        event.type = 1  # INPUT_KEYBOARD
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

    def physical_geometry(self, x, y):
        if not (0 <= x < self.request_width and 0 <= y < self.request_height):
            raise ValueError("Requested pointer is outside the bound client area")
        previous = self.user.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))  # PMv2, this thread only.
        if not previous:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            rect = wintypes.RECT()
            if not self.user.GetClientRect(self.hwnd, ctypes.byref(rect)):
                raise ctypes.WinError(ctypes.get_last_error())
            width, height = rect.right - rect.left, rect.bottom - rect.top
            if width <= 0 or height <= 0:
                raise RuntimeError("Physical target client area is empty")
            origin = wintypes.POINT(0, 0)
            if not self.user.ClientToScreen(self.hwnd, ctypes.byref(origin)):
                raise ctypes.WinError(ctypes.get_last_error())
            point = wintypes.POINT(min(width - 1, round(x * width / self.request_width)),
                                  min(height - 1, round(y * height / self.request_height)))
            if not self.user.ClientToScreen(self.hwnd, ctypes.byref(point)):
                raise ctypes.WinError(ctypes.get_last_error())
            virtual = tuple(self.user.GetSystemMetrics(index) for index in (76, 77, 78, 79))
            if virtual[2] <= 1 or virtual[3] <= 1:
                raise RuntimeError("Invalid physical virtual-desktop dimensions")
            geometry = (origin.x, origin.y, width, height, *virtual)
            self.mouse_evidence.update(mouse_request_x=x, mouse_request_y=y,
                                       mouse_physical_target_x=point.x, mouse_physical_target_y=point.y,
                                       mouse_physical_client_left=origin.x, mouse_physical_client_top=origin.y,
                                       mouse_physical_client_width=width, mouse_physical_client_height=height,
                                       mouse_scale_x=width / self.request_width, mouse_scale_y=height / self.request_height,
                                       mouse_virtual_left=virtual[0], mouse_virtual_top=virtual[1],
                                       mouse_virtual_width=virtual[2], mouse_virtual_height=virtual[3])
            return point, geometry
        finally:
            if not self.user.SetThreadDpiAwarenessContext(ctypes.c_void_p(previous)):
                raise ctypes.WinError(ctypes.get_last_error())
            self.mouse_evidence["mouse_thread_dpi_restored"] = "true"

    def verify_physical_hit(self, point, geometry):
        self.target.validate()
        self.foreground()
        left, top, width, height = geometry[:4]
        hit = self.user.WindowFromPhysicalPoint(point)
        hit_pid = wintypes.DWORD()
        if hit:
            self.user.GetWindowThreadProcessId(hit, ctypes.byref(hit_pid))
        self.mouse_evidence.update(mouse_physical_hit_hwnd=hex(hit or 0),
                                   mouse_physical_hit_pid=hit_pid.value,
                                   mouse_actual_physical_x=point.x, mouse_actual_physical_y=point.y)
        self.mouse_evidence.pop("mouse_hit_rejected", None)
        if not (left <= point.x < left + width and top <= point.y < top + height):
            self.mouse_evidence["mouse_hit_rejected"] = "outside owned client area"
            raise RuntimeError("Physical pointer is outside the owned target client area")
        if not self.owned(self.hwnd) or not self.owned(hit):
            self.mouse_evidence["mouse_hit_rejected"] = "physical hit has different ownership"
            raise RuntimeError("Physical pointer hit window does not belong to the bound Java PID")

    def physical_cursor(self):
        point = wintypes.POINT()
        if not self.user.GetPhysicalCursorPos(ctypes.byref(point)):
            raise ctypes.WinError(ctypes.get_last_error())
        return point

    def send_mouse(self, flags, dx=0, dy=0, down_hold=None, release_button=None, cleanup_only=False):
        if cleanup_only:
            if release_button not in self.buttons or dx or dy or flags != (0x0004 if release_button == 0 else 0x0010):
                raise RuntimeError("Cleanup may only release a mouse button successfully injected by this bridge")
        else:
            self.target.validate()
            if not self.owned(self.hwnd):
                raise RuntimeError("Mouse target no longer belongs to the bound Java PID")
            self.foreground()
        event = self.Input()
        event.type = 0  # INPUT_MOUSE
        event.value.mouse = self.MouseInput(dx, dy, 0, flags, 0, 0)
        if self.user.SendInput(1, ctypes.byref(event), ctypes.sizeof(event)) != 1:
            raise ctypes.WinError(ctypes.get_last_error())
        # Update ownership immediately after insertion, before evidence or any further checks.
        if down_hold is not None:
            button, x, y = down_hold
            self.buttons[button] = (x, y)
        if release_button is not None:
            del self.buttons[release_button]
        self.mouse_evidence["mouse_sendinput_flags"] = hex(flags)
        self.mouse_evidence["mouse_cleanup_only"] = str(cleanup_only).lower()

    def cleanup_mouse(self, button, reason, context_changed=True):
        self.mouse_evidence.update(mouse_cleanup_only="true", mouse_context_changed=str(context_changed).lower(),
                                   mouse_cleanup_reason=reason)
        self.send_mouse(0x0004 if button == 0 else 0x0010, release_button=button, cleanup_only=True)
        released = self.mouse_evidence.get("mouse_cleanup_released_buttons", "")
        self.mouse_evidence["mouse_cleanup_released_buttons"] = (released + "," if released else "") + str(button)
        print(f"CLEANUP owned mouse-up button={button} cleanup_only=true context_changed={context_changed} reason={reason}", flush=True)

    def mouse_move(self, x, y):
        point, geometry = self.physical_geometry(x, y)
        self.verify_physical_hit(point, geometry)
        vx, vy, vw, vh = geometry[4:]
        if not (vx <= point.x < vx + vw and vy <= point.y < vy + vh):
            raise RuntimeError("Target point is outside the physical virtual desktop")
        dx = round((point.x - vx) * 65535 / (vw - 1))
        dy = round((point.y - vy) * 65535 / (vh - 1))
        self.mouse_evidence.update(mouse_absolute_dx=dx, mouse_absolute_dy=dy,
                                   mouse_route="UI absolute SendInput; temporary thread DPI context")
        self.send_mouse(0x0001 | 0x8000 | 0x4000, dx, dy)
        limit = time.monotonic() + .5
        while True:
            self.target.validate()
            self.foreground()
            stop_file = getattr(self, "stop_file", None)
            if stop_file is not None and stop_file.exists():
                raise RuntimeError("Owned launcher requested shutdown while verifying pointer move")
            actual = self.physical_cursor()
            if abs(actual.x - point.x) <= 1 and abs(actual.y - point.y) <= 1:
                self.verify_physical_hit(actual, geometry)
                self.pending_pointer = (x, y)
                return
            if time.monotonic() >= limit:
                raise RuntimeError("Actual physical cursor did not reach the verified UI point")
            time.sleep(.02)

    def mouse(self, button, down, x=0, y=0):
        if down:
            if button in self.buttons:
                raise ValueError("Duplicate mouse-down")
            if self.user.GetAsyncKeyState(1 if button == 0 else 2) & 0x8000:
                raise RuntimeError("Physical mouse button is already held; refusing ownership")
        elif button not in self.buttons:
            raise ValueError("Cannot release a mouse button this bridge did not press")
        if not down:
            x, y = self.buttons[button]
            try:
                point, geometry = self.physical_geometry(x, y)
                self.verify_physical_hit(self.physical_cursor(), geometry)
                self.mouse_evidence["mouse_route"] = "owned-button SendInput release; no pointer movement"
                self.send_mouse(0x0004 if button == 0 else 0x0010, release_button=button)
                return
            except Exception as context_error:
                if button in self.buttons:
                    owned_warp = False
                    if self.mouse_evidence.get("mouse_hit_rejected"):
                        try:
                            self.target.validate()
                            owned_warp = self.owned(self.hwnd) and self.user.GetForegroundWindow() == self.hwnd
                        except Exception:
                            pass
                    self.cleanup_mouse(button, f"normal mouse-up context changed: {context_error}",
                                       context_changed="physical_cursor_warp" if owned_warp else True)
                    if owned_warp:
                        self.mouse_evidence.update(mouse_release_only_warp="true",
                                                   mouse_route="owned foreground cursor warp: UP-only release; no movement")
                        return
                raise RuntimeError("Owned mouse button released for cleanup; normal mouse-up context changed") from context_error
        point, geometry = self.physical_geometry(x, y)
        actual = self.physical_cursor()
        self.verify_physical_hit(actual, geometry)
        if down and self.pending_pointer is not None:
            if self.pending_pointer != (x, y) or abs(actual.x - point.x) > 1 or abs(actual.y - point.y) > 1:
                raise RuntimeError("UI button request does not match the verified physical hover point")
            self.mouse_evidence["mouse_route"] = "button-only SendInput after separate verified UI move"
        elif down:
            if (x, y) != (self.request_width // 2, self.request_height // 2):
                raise RuntimeError("Noncentral click requires a preceding explicit UI mouse-move request")
            self.mouse_evidence["mouse_route"] = "world-centre button-only SendInput; no pointer movement"
        self.send_mouse(0x0002 if button == 0 else 0x0008, down_hold=(button, x, y))
        self.pending_pointer = None

    def release(self):
        errors = []
        for button in list(self.buttons):
            try:
                self.cleanup_mouse(button, "release-all/finally: release only this bridge's injected button",
                                   context_changed="not_checked")
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
        self.bind_evidence = {}
        self.focus_evidence = {}
        self.mouse_evidence = {}
        operation = request["operation"]
        try:
            evidence = self.bind(request)
            if operation == "release-all":
                self.release()
            else:
                self.foreground(focus=operation == "focus")
                if operation == "focus":
                    # Establish an owned physical pointer before the first button-only
                    # world gesture. Later world clicks never reposition the pointer.
                    self.mouse_move(request["x"], request["y"])
                    self.pending_pointer = None
                    self.mouse_evidence.update(mouse_focus_centre="true",
                                               mouse_route="focus-stage owned-client-centre SendInput")
                elif operation.startswith("key-"):
                    self.keyboard(request["key"], operation == "key-down")
                elif operation == "mouse-move":
                    self.mouse_move(request["x"], request["y"])
                elif operation.startswith("mouse-"):
                    self.mouse(request["key"], operation == "mouse-down", request["x"], request["y"])
        except Exception as context_error:
            # Bind/foreground checks may fail before mouse() sees the UP. Release our
            # global hold first, then reject the request with truthful cleanup evidence.
            if operation == "mouse-up" and request["key"] in self.buttons:
                self.cleanup_mouse(request["key"], f"mouse-up pre-dispatch context changed: {context_error}")
            elif operation == "release-all":
                self.release()
            raise
        return {**self.bind_evidence, **evidence, **self.focus_evidence, **self.mouse_evidence}


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
    bridge.stop_file = receipts / "cut-os-input-bridge-stop.txt"
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
                                                      "failure": str(error), **bridge.bind_evidence,
                                                      **bridge.focus_evidence, **bridge.mouse_evidence})
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
