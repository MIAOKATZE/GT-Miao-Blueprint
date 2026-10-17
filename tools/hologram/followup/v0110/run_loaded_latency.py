"""ROOT-only loaded-workspace common-version latency launcher; safety lifecycle copied from run_native.py."""

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
import zipfile

import psutil


def standard_namespace(path):
    """Normalize an already resolved Win32 drive/UNC name, never a device namespace.

    ntpath.realpath may retain GetFinalPathNameByHandle's extended prefix when
    its second, unprefixed lookup races an atomic file replacement. Normalize
    both operands before containment; do not resolve again or ignore failures.
    """
    value = os.fspath(path)
    if os.name != "nt":
        return Path(value)
    value = value.replace("/", "\\")
    folded = value.casefold()
    if folded.startswith("\\\\?\\unc\\"):
        value = "\\\\" + value[8:]
    elif folded.startswith("\\\\?\\"):
        if not re.match(r"^[A-Za-z]:\\", value[4:]):
            raise ValueError("Unsupported extended device namespace")
        value = value[4:]
    elif folded.startswith(("\\\\.\\", "\\??\\")):
        raise ValueError("Device namespaces are not permitted")
    normalized = Path(value)
    if not normalized.is_absolute():
        raise ValueError("Canonical containment paths must be absolute")
    # Removing an extended prefix must not change trailing-dot/space, ADS, or
    # reserved DOS-device semantics. None are legitimate fixture filenames.
    for part in normalized.parts[1:]:
        if part.endswith((".", " ")) or ":" in part or re.match(
                r"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", part, re.I):
            raise ValueError("Ambiguous Win32 filename in canonical path")
    return normalized


ROOT = standard_namespace(Path(__file__).resolve()).parents[4]
BUILD = ROOT / "build"
FIXTURE = BUILD / "hologram-packaged-v018-mui"
OUTPUTS = BUILD / "verification/render-v0110"
PORT = 25678
TIMEOUT_SECONDS = 1200
PRESERVED_ADDON_PREFIX = "gtmb-v016-production-gui-probe"


class ProductFailure(RuntimeError):
    pass


class IntegrityFailure(RuntimeError):
    pass


def within(path, parent, *, exists=False):
    """Resolve junctions/symlinks before every intended file or directory operation."""
    resolved = standard_namespace(Path(path).resolve(strict=exists))
    boundary = standard_namespace(Path(parent).resolve(strict=True))
    resolved.relative_to(boundary)
    return resolved


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, value):
    path = within(path, ROOT)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def assert_idle():
    """Never terminate a pre-existing JVM: all workspace-owned Java must already be stopped."""
    prefix = ROOT.as_posix().casefold().rstrip("/")
    for process in psutil.process_iter(["pid", "name"]):
        if (process.info["name"] or "").casefold() not in ("java.exe", "javaw.exe", "java"):
            continue
        try:
            cwd = standard_namespace(Path(process.cwd()).resolve()).as_posix().casefold().rstrip("/")
            command = " ".join(process.cmdline()).replace("\\", "/").casefold()
        except psutil.NoSuchProcess:
            continue
        except psutil.AccessDenied as error:
            raise RuntimeError(f"Cannot establish Java PID {process.pid} ownership; no staging") from error
        if cwd == prefix or cwd.startswith(prefix + "/") or prefix in command:
            raise RuntimeError(f"Workspace-owned Java PID {process.pid} still exists; no staging")


def assert_port_available():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        probe.bind(("127.0.0.1", PORT))


def argfile_tokens(text):
    """The prepared files contain exactly one argument per nonempty line."""
    values = []
    for line in text.lstrip("\ufeff").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith('"'):
            if not line.endswith('"') or '"' in line[1:-1]:
                raise ValueError("Prepared argument line has unsupported embedded quoting")
            line = line[1:-1]
        elif '"' in line:
            raise ValueError("Prepared argument line has unsupported quoting")
        if line.startswith("@"):
            raise ValueError("Nested prepared JVM argument files are not permitted")
        values.append(line)
    return values


def write_argfile(path, tokens):
    # Java argument files interpret backslash escapes, even inside a quoted argument.
    text = "\n".join('"' + token.replace("\\", "\\\\").replace('"', '\\"') + '"'
                     for token in tokens) + "\n"
    within(path, ROOT).write_text(text, encoding="utf-8")


def validate_heap(tokens, expected):
    actual = [value for value in tokens if value.startswith("-Xmx")]
    if actual != [expected]:
        raise ValueError(f"Exactly one {expected} required, observed {actual}")


def common_flags(output, large, cells):
    return [
        f"-Dgtmb.hologram.followupRoot={FIXTURE.as_posix()}",
        f"-Dgtmb.v0110.output={output.as_posix()}",
        f"-Dgtmb.v0110.largeEdit={str(large).lower()}",
        f"-Dgtmb.v0110.cells={cells}",
        "-Dgtmb.hologram.cutNativeClient=false",
        "-Dgtmb.hologram.cutNativeServer=false",
        "-Dgtmb.v016.productionGui=false",
        "-Dgtmb.v019.backgroundClient=false",
        "-Dgtmb.v019.backgroundServer=false",
        "-Dgtmb.v019.ctmClient=false",
        "-Dgtmb.v019.downloadClient=false",
        "-Dgtmb.hologram.clientSmoke=false",
        "-Dgtmb.hologram.sulfurLineClient=false",
        "-Dgtmb.hologram.followupWorkbenchClient=false",
        "-Dgtmb.hologram.followupProductionFlow=false",
        "-Dgtmb.hologram.productionPreviewSmoke=false",
    ]


def client_arguments(output, large, cells):
    prepared = argfile_tokens(within(FIXTURE / "java-args.txt", FIXTURE, exists=True).read_text(encoding="utf-8"))
    validate_heap(prepared, "-Xmx4096M")
    tokens = [value for value in prepared if not value.startswith("-Dgtmb.")
              and not re.match(r"-Xm[sx]", value)]
    offline_name = "GTMBProbeV0110"
    offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + offline_name).encode()).digest(), version=3)
    for key, value in (("--username", offline_name), ("--uuid", offline_uuid.hex),
                       ("--gameDir", (FIXTURE / "client").as_posix())):
        if tokens.count(key) != 1 or tokens.index(key) + 1 >= len(tokens):
            raise ValueError(f"Prepared client arguments require exactly one {key}")
        tokens[tokens.index(key) + 1] = value
    native = [value for value in tokens if value.startswith("-Djava.library.path=")]
    if len(native) != 1:
        raise ValueError("Prepared client arguments require one native library directory")
    within(Path(native[0].split("=", 1)[1]), FIXTURE / "client", exists=True)
    if tokens.count("-cp") != 1:
        raise ValueError("Prepared client arguments require an explicit -cp boundary")
    additions = ["-Xms768M", "-Xmx4096M", *common_flags(output, large, cells),
                 "-Dgtmb.v0110.backgroundClient=true", f"-Dgtmb.v0110.address=127.0.0.1:{PORT}",
                 f"-Dgtmb.hologram.backgroundProbe={str(not large).lower()}",
                 f"-Dgtmb.hologram.previewCaptureWitness={str(not large).lower()}",
                 f"-Dgtmb.hologram.backgroundProbeDir={output.as_posix()}"]
    index = tokens.index("-cp")
    tokens[index:index] = additions
    validate_heap(tokens, "-Xmx4096M")
    return tokens


def server_arguments(output, large, cells):
    prepared = argfile_tokens(within(FIXTURE / "server/java9args.txt", FIXTURE, exists=True).read_text(encoding="utf-8"))
    tokens = [value for value in prepared if not value.startswith("-Dgtmb.")
              and not re.match(r"-Xm[sx]", value)]
    tokens += ["-Xms1G", "-Xmx6G", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890",
               "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890", *common_flags(output, large, cells),
               "-Dgtmb.v0110.backgroundServer=true", "-Dgtmb.hologram.backgroundProbe=false", "-jar",
               within(FIXTURE / "server/lwjgl3ify-forgePatches.jar", FIXTURE, exists=True).as_posix(), "nogui"]
    validate_heap(tokens, "-Xmx6G")
    return tokens


def changed_server_properties(original, world):
    lines = original.decode("latin-1").splitlines()
    changes = {"level-name": world, "server-ip": "127.0.0.1", "server-port": str(PORT),
               "online-mode": "false", "max-players": "1"}
    for key, value in changes.items():
        lines = [line for line in lines if not re.match(r"\s*" + re.escape(key) + r"\s*[=:]", line)]
        lines.append(f"{key}={value}")
    return ("\n".join(lines) + "\n").encode("latin-1")


RECEIPT_LEAVES = frozenset(("latency-client.properties", "latency-server.properties",
                            "client-latency.properties", "server-tick-and-stage-metrics.properties"))
RECEIPT_READ_ATTEMPTS = 8


class _ReceiptReplaced(Exception):
    """A known NTFS delete-pending leaf: discard this observation and reopen by name."""


def receipt_named_path(path):
    """Canonicalize the stable parent, retaining only a whitelisted plain receipt leaf."""
    path = standard_namespace(Path(path).absolute())
    if path.name not in RECEIPT_LEAVES:
        raise ValueError("Only known atomically replaced latency receipt leaves may use shared reads")
    parent = within(path.parent, OUTPUTS, exists=True)
    outputs = within(OUTPUTS, ROOT, exists=True)
    if parent.parent != outputs or not re.fullmatch(r"[A-Za-z0-9_-]{1,20}", parent.name):
        raise ValueError("Receipt must be directly inside a canonical owned case directory")
    return parent / path.name


def receipt_target(resolved, named):
    """Retry only the exact same-volume NTFS $Extend/$Deleted race, never outside links."""
    resolved = standard_namespace(resolved)
    parts = resolved.parts
    if (os.name == "nt" and len(parts) == 4 and resolved.drive.casefold() == named.drive.casefold()
            and parts[1].casefold() == "$extend" and parts[2].casefold() == "$deleted"
            and re.fullmatch(r"[0-9a-fA-F]{16,128}", parts[3])):
        raise _ReceiptReplaced(str(resolved))
    resolved.relative_to(named.parent)  # Outside symlink/junction targets remain immediate failures.
    if resolved != named:
        raise ValueError("Receipt leaf redirected away from its canonical owned name")


def _read_shared_receipt(path):
    """Open the current name; validate the actual Windows handle both before and after reading."""
    if os.name != "nt":
        try:
            with path.open("rb") as stream:
                receipt_target(path.resolve(strict=True), path)
                data = stream.read()
                receipt_target(path.resolve(strict=False), path)
                return data
        except FileNotFoundError:
            return b""
    import msvcrt
    api = ctypes.WinDLL("kernel32", use_last_error=True)
    api.CreateFileW.restype = ctypes.c_void_p
    api.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p,
                               ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
    api.GetFinalPathNameByHandleW.restype = ctypes.c_ulong
    api.GetFinalPathNameByHandleW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p,
                                           ctypes.c_ulong, ctypes.c_ulong]
    api.CloseHandle.argtypes = [ctypes.c_void_p]
    api.CloseHandle.restype = ctypes.c_int
    handle = api.CreateFileW(str(path), 0x80000000, 7, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        code = ctypes.get_last_error()
        if code in (2, 3):
            return b""
        raise ctypes.WinError(code)
    def verify_handle():
        size = 1024
        while True:
            buffer = ctypes.create_unicode_buffer(size)
            length = api.GetFinalPathNameByHandleW(handle, buffer, size, 0)
            if not length:
                raise ctypes.WinError(ctypes.get_last_error())
            if length < size:
                receipt_target(Path(buffer.value), path)
                return
            size = length + 1

    try:
        verify_handle()  # Never read bytes from a handle redirected outside the owned case.
        descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except BaseException:
        api.CloseHandle(handle)
        raise
    with os.fdopen(descriptor, "rb") as stream:
        data = stream.read()
        verify_handle()  # A replaced/deleted handle's bytes are discarded, even if read succeeded.
        return data


def shared_bytes(path):
    """Bounded retry of known atomic receipt races; global within() remains strict."""
    for attempt in range(RECEIPT_READ_ATTEMPTS):
        named = receipt_named_path(path)  # Revalidate canonical parent on EVERY attempt.
        try:
            receipt_target(named.resolve(strict=False), named)
            return _read_shared_receipt(named)
        except _ReceiptReplaced as replaced:
            if attempt + 1 == RECEIPT_READ_ATTEMPTS:
                raise RuntimeError("Receipt remained NTFS delete-pending after bounded reopen retries") from replaced
            time.sleep(0.01)


def properties(path):
    text = shared_bytes(path).decode("latin-1")
    result = {}
    for line in text.splitlines():
        if not line or line.lstrip().startswith(("#", "!")):
            continue
        # Required status/token fields are plain ASCII Java Properties; decode escaped diagnostics too.
        match = re.match(r"((?:\\.|[^=:\s])+)[=:](.*)", line)
        if match:
            value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda item: chr(int(item[1], 16)), match[2])
            result[match[1]] = re.sub(r"\\(.)", lambda item: {"n": "\n", "r": "\r", "t": "\t"}.get(item[1], item[1]), value)
    return result


def verify_jar(path, member):
    path = within(path, BUILD, exists=True)
    if path.is_relative_to(FIXTURE) or path.suffix.lower() != ".jar" or not path.name.casefold().startswith("gtmb"):
        raise ValueError("Artifact sources must be gtmb*.jar files under workspace build, outside the live fixture")
    if path.name.casefold().startswith(PRESERVED_ADDON_PREFIX):
        raise ValueError("The preserved v016 test probe cannot be replaced")
    with zipfile.ZipFile(path) as archive:
        if member not in archive.namelist():
            raise ValueError(f"Selected JAR lacks expected member {member}")
    return path


def run(args):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,20}", args.name):
        raise ValueError("--name requires 1-20 ASCII letters/digits/underscore/hyphen")
    args.large_edit = True
    cells = args.cells if args.cells is not None else 50000 if args.large_edit else 4096
    timeout_seconds = args.timeout_seconds
    within(BUILD, ROOT, exists=True)
    fixture = within(FIXTURE, BUILD, exists=True)
    if fixture != FIXTURE.absolute():
        raise ValueError("Only the existing owned fixture is permitted")
    if standard_namespace(OUTPUTS.resolve()) != OUTPUTS.absolute():
        raise ValueError("Case output root must not redirect through a junction/symlink")
    for role in ("client", "server"):
        within(fixture / role / "mods", fixture, exists=True)
    jar = verify_jar(args.jar, "com/miaokatze/gtmb/client/hologram/HologramScreen.class")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", args.expect_product_sha256) or sha(jar) != args.expect_product_sha256.lower():
        raise IntegrityFailure("Explicit expected product SHA-256 differs")
    if not 120 <= args.timeout_seconds <= 3600:
        raise ValueError("Deadline must be 120..3600 seconds")
    addon = verify_jar(args.addon, "gtmb/v0110/probe/V0110LoadedLatencyProbe.class")
    with zipfile.ZipFile(addon) as archive:
        if any(name.endswith(".class") and not name.startswith("gtmb/v0110/probe/")
               for name in archive.namelist()):
            raise IntegrityFailure("Latency addon may contain only independent probe classes")
    if jar.name.casefold() == addon.name.casefold():
        raise ValueError("Product and addon destinations must be distinct")
    output = within(OUTPUTS / args.name, BUILD)
    if output.exists():
        raise FileExistsError("Fresh case output required; choose a new --name")
    world_name = "hologram-v0110-" + args.name
    world = within(fixture / "server" / world_name, fixture)
    if world.exists():
        raise FileExistsError("Fresh named world required; choose a new --name")
    # Runtime executable is read-only and may live outside the workspace, as recorded by this fixture.
    environment_path = within(fixture / "environment.json", fixture, exists=True)
    environment = json.loads(environment_path.read_text(encoding="utf-8"))
    java = standard_namespace(Path(environment["java"]).resolve(strict=True))
    if not java.is_file() or java.name.casefold() not in ("java.exe", "java"):
        raise ValueError("Fixture environment must identify an existing Java executable")
    assert_idle()
    assert_port_available()
    if within(fixture / "receipts/cut-os-input-bridge.lock", fixture).exists():
        raise RuntimeError("Existing CUT input bridge lock requires root inspection before this no-OS-input case")
    client_tokens = client_arguments(output, args.large_edit, cells)
    index = client_tokens.index("-cp")
    client_tokens[index:index] = latency_flags(args, "client")
    client_tokens = [v for v in client_tokens if not v.startswith("-Dgtmb.v0110.backgroundClient=")]
    server_tokens = server_arguments(output, args.large_edit, cells)
    index = server_tokens.index("-jar")
    server_tokens[index:index] = latency_flags(args, "server")
    server_tokens = [v for v in server_tokens if not v.startswith("-Dgtmb.v0110.backgroundServer=")]
    targets = {
        "server-properties": within(fixture / "server/server.properties", fixture, exists=True),
        "server-library-config": within(fixture / "server/config/gtmb/server-blueprint-library.cfg", fixture),
        "client-ui-preferences": within(fixture / "client/config/gtmb-hologram-ui.properties", fixture),
    }
    if not args.large_edit:
        targets["observer-workstation-screenshot"] = within(
            fixture / "client/screenshots/v0110-background-workstation.png", fixture)
    originals = {key: path.read_bytes() if path.is_file() else None for key, path in targets.items()}
    preserved_addons = {str(within(path, fixture, exists=True)): sha(path)
                        for role in ("client", "server")
                        for path in (fixture / role / "mods").iterdir()
                        if path.is_file() and path.name.casefold().startswith(PRESERVED_ADDON_PREFIX)
                        and path.suffix.casefold() == ".jar"}
    output.mkdir(parents=True)
    before = within(output / "before", output)
    before.mkdir()
    backups = {}
    for key, path in targets.items():
        data = originals[key]
        if data is not None:
            (before / (key + ".bin")).write_bytes(data)
        backups[key] = {"path": str(path), "existed": data is not None,
                        "sha256": hashlib.sha256(data).hexdigest() if data is not None else None,
                        "backup": str(before / (key + ".bin")) if data is not None else None}
    save(before / "files.json", backups)
    client_args = output / "client.args"
    server_args = output / "server.args"
    write_argfile(client_args, client_tokens)
    write_argfile(server_args, server_tokens)
    commands = {"client": [str(java), "@" + str(client_args)], "server": [str(java), "@" + str(server_args)]}
    result = {
        "name": args.name, "status": "RUNNING", "mode": f"loaded-latency-{args.phase}-{cells}",
        "phase_mode": args.phase,
        "requested_source_cells": cells,
        "source_dimensions_xyz": [80, cells // 2000, 25],
        "native_scale_claim": "Pending actual full capture/hydration/render/delta; volume eligibility does not prove byte-budget or resource support",
        "scope": "Actual dedicated Netty and natural rendered MUI stimulus; no OS input; no user instance/world copied",
        "fixture": str(fixture), "output": str(output), "receipts": str(output), "world": str(world),
        "normal_source": str(jar), "normal_sha256": sha(jar), "addon_source": str(addon), "addon_sha256": sha(addon),
        "java": str(java), "java_sha256": sha(java), "environment_sha256": sha(environment_path),
        "client_Xmx": "4096M", "server_Xmx": "6G", "client_peak_rss_bytes": 0,
        "commands": commands, "argfiles_sha256": {"client": sha(client_args), "server": sha(server_args)},
        "port": PORT, "started_unix": time.time(), "timeout_seconds": timeout_seconds,
        "before_files": backups, "processes": {}, "moved_originals": [], "installed": [],
        "preserved_v016_addons": preserved_addons,
        "cleanup_errors": [], "addonsCleanup": False, "processClosed": False,
        "user_instance_modified": False, "worlds_copied": False,
    }
    save(output / "commands.json", commands)
    save(output / "stage.json", result)
    processes, logs = {}, {}
    touched = False

    def publish():
        save(output / "runtime.json", result)

    def launch(role):
        log = (output / (role + ".log")).open("xb")
        logs[role] = log
        process = subprocess.Popen(commands[role], cwd=fixture / role,
                                   stdin=subprocess.PIPE if role == "server" else subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        processes[role] = process
        result["processes"][role] = {"pid": process.pid, "command": commands[role], "cwd": str(fixture / role),
                                     "started_unix": time.time(), "forced_stop": False, "processClosed": False,
                                     "hidden_console": os.name == "nt"}
        publish()
        return process

    try:
        assert_idle()
        assert_port_available()
        # All complete-byte backups precede the first live-fixture mutation.
        touched = True
        targets["server-properties"].write_bytes(changed_server_properties(originals["server-properties"], world_name))
        for role in ("client", "server"):
            mods = within(fixture / role / "mods", fixture, exists=True)
            staging = within(output / ("staging-original-" + role), output)
            staging.mkdir()
            entries = sorted(path for path in mods.iterdir()
                             if path.is_file() and path.suffix.casefold() == ".jar"
                             and path.name.casefold().startswith("gtmb")
                             and not path.name.casefold().startswith(PRESERVED_ADDON_PREFIX))
            for path in entries:
                source = within(path, fixture, exists=True)
                destination = within(staging / path.name, output)
                item = {"role": role, "original": str(source), "staging": str(destination), "sha256": sha(source)}
                source.rename(destination)
                result["moved_originals"].append(item)
                publish()
            for source, kind in ((jar, "normal"), (addon, "addon")):
                destination = within(mods / source.name, fixture)
                if destination.exists():
                    raise IntegrityFailure(f"Unowned destination appeared after staging: {destination}")
                item = {"role": role, "kind": kind, "path": str(destination), "sha256": sha(source), "existed_before": False}
                result["installed"].append(item)
                publish()  # A partial copy is still owned and must be archived during cleanup.
                shutil.copy2(source, destination)
                if sha(destination) != item["sha256"]:
                    raise IntegrityFailure("Installed artifact digest differs")
        assert_idle()
        assert_port_available()
        deadline = time.monotonic() + timeout_seconds
        server = launch("server")
        startup_deadline = min(deadline, time.monotonic() + 600)
        while not re.search(r"Done \([0-9.,]+s\)!", (output / "server.log").read_text(encoding="utf-8", errors="replace")):
            peer = properties(output / "latency-server.properties")
            if peer.get("status") == "FAIL":
                raise ProductFailure(peer.get("failure", "Server native probe failed during startup"))
            if (output / "abort.request").exists():
                raise InterruptedError("Root requested shutdown through abort.request")
            if server.poll() is not None:
                raise RuntimeError("Server exited before its normal Done marker")
            if time.monotonic() >= startup_deadline:
                raise TimeoutError("Server startup deadline expired")
            time.sleep(0.5)
        client = launch("client")
        monitor = psutil.Process(client.pid)
        created = monitor.create_time()
        last_limit_check = 0.0
        while time.monotonic() < deadline:
            if client.poll() is None:
                try:
                    if monitor.create_time() != created:
                        raise IntegrityFailure("Client PID identity changed")
                    result["client_peak_rss_bytes"] = max(result["client_peak_rss_bytes"], monitor.memory_info().rss)
                except psutil.NoSuchProcess:
                    pass
            evidence = properties(output / "latency-client.properties")
            peer = properties(output / "latency-server.properties")
            result["native_evidence"], result["server_evidence"] = evidence, peer
            if args.large_edit and time.monotonic() - last_limit_check >= 5:
                last_limit_check = time.monotonic()
                for role in ("server", "client"):
                    log_path = output / (role + ".log")
                    if not log_path.is_file():
                        continue
                    with log_path.open("rb") as log_stream:
                        log_stream.seek(0, os.SEEK_END)
                        log_stream.seek(max(0, log_stream.tell() - 65536))
                        tail = log_stream.read().decode("utf-8", errors="replace")
                    limit = re.search(r"[^\r\n]*(?:IOException[^\r\n]*snapshot byte budget|OutOfMemoryError)[^\r\n]*", tail)
                    if limit:
                        raise ProductFailure(f"Actual native limit/resource failure ({role}): {limit[0][:2048]}")
            for role, receipt in (("client", evidence), ("server", peer)):
                if receipt.get("status") == "FAIL":
                    raise ProductFailure(role + ": " + receipt.get("failure", "Native probe reported FAIL"))
            if (output / "abort.request").exists():
                raise InterruptedError("Root requested shutdown through abort.request")
            if all(process.poll() is not None for process in processes.values()):
                if not (evidence.get("status") == "PASS" and evidence.get("preferencesRestored") == "true"
                        and peer.get("status") == "COMPLETE" and peer.get("configRestored") == "true"
                        and peer.get("clientVerdict") == "PASS"):
                    raise RuntimeError("Natural exits lack complete clientPASS/serverCOMPLETE/configRestored/clientVerdict receipts")
                if not all(process.returncode == 0 for process in processes.values()):
                    raise RuntimeError("Native terminal receipts accompanied nonzero process exit")
                for item in result["installed"]:
                    if sha(within(item["path"], fixture, exists=True)) != item["sha256"]:
                        raise IntegrityFailure("Installed artifact changed during native run")
                expected_rounds = "0" if args.phase == "import" else "7"
                if evidence.get("selectionCompletedRounds") != expected_rounds:
                    raise IntegrityFailure("Missing two warm + five measured common API rounds")
                if args.phase != "selection" and evidence.get("actualCapturedCells") != str(cells):
                    raise IntegrityFailure("Actual installed client volume differs from requested scale")
                if peer.get("actualSourceBlocks") != str(cells):
                    raise IntegrityFailure("Actual world source volume differs from requested scale")
                if not all((output / name).is_file() for name in (
                        "client-requests.tsv", "client-latency.properties", "server-ticks.tsv",
                        "server-stage-transitions.tsv", "server-tick-and-stage-metrics.properties")):
                    raise RuntimeError("Missing real request/codec/tick/stage evidence")
                if not (evidence.get("loadedInteractionCompleted") == "true"
                        and evidence.get("loadedSettingsNaturalRender") == "true"
                        and evidence.get("loadedPromptAuthoritative") == "true"
                        and evidence.get("loadedClientCaptureRetained") == "true"
                        and peer.get("loadedServerCaptureObserved") == "true"
                        and peer.get("loadedServerPromptConfirmed") == "true"
                        and peer.get("loadedServerCaptureCells") == str(cells)):
                    raise IntegrityFailure("Loaded interaction lacks retained large capture, natural UI, or real prompt authority")
                result["status"] = "PASS"
                result["natural_terminal"] = True
                break
            for role, receipt in (("client", evidence), ("server", peer)):
                if processes[role].poll() is not None and receipt.get("status") not in ("PASS", "COMPLETE"):
                    raise RuntimeError(role + " exited before its real terminal receipt")
            time.sleep(0.2)
        else:
            raise ProductFailure("Latency case deadline expired: incomplete measurement, never PASS")
    except BaseException as error:
        result["status"] = "FAIL" if isinstance(error, (ProductFailure, IntegrityFailure)) else "INCONCLUSIVE"
        result["failure"] = f"{type(error).__name__}: {error}"
    finally:
        # Only handles created by this runner are stopped; pre-existing JVMs were rejected before mutation.
        server = processes.get("server")
        if server is not None and server.poll() is None:
            try:
                server.stdin.write(b"stop\n")
                server.stdin.flush()
            except OSError:
                pass
        for role in ("server", "client"):
            process = processes.get(role)
            if process is None:
                continue
            try:
                if process.poll() is None:
                    try:
                        process.wait(timeout=20 if role == "server" else 5)
                    except subprocess.TimeoutExpired:
                        result["processes"][role]["forced_stop"] = True
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=10)
                result["processes"][role].update(exit_code=process.poll(), processClosed=process.poll() is not None)
            except BaseException as error:
                result["cleanup_errors"].append(f"close {role}: {type(error).__name__}: {error}")
        for log in logs.values():
            log.close()
        result["processClosed"] = all(process.poll() is not None for process in processes.values())
        try:
            assert_idle()
            result["ownedJavaIdleAfter"] = True
        except Exception as error:
            result["ownedJavaIdleAfter"] = False
            result["cleanup_errors"].append(f"post-run ownership check: {error}")
        if not result["processClosed"]:
            result["cleanup_errors"].append("Owned JVM remains alive; do not restore files underneath it")
        elif touched:
            # Every cleanup step is independent: a JAR rename failure must not prevent preference/config restoration.
            for item in result["installed"]:
                try:
                    path = within(item["path"], fixture)
                    archive = within(output / ("staged-used-" + item["role"]) / path.name, output)
                    archive.parent.mkdir(exist_ok=True)
                    if path.exists():
                        if archive.exists():
                            raise IntegrityFailure("Installed-artifact archive collision")
                        item["final_sha256"] = sha(path)
                        path.rename(archive)
                        item["archived"] = str(archive)
                    item["removed_from_fixture"] = not path.exists()
                except BaseException as error:
                    result["cleanup_errors"].append(f"archive installed {item['path']}: {error}")
            for item in reversed(result["moved_originals"]):
                try:
                    source = within(item["staging"], output, exists=True)
                    destination = within(item["original"], fixture)
                    if destination.exists():
                        conflict = within(output / ("unexpected-before-restore-" + item["role"]) / destination.name, output)
                        conflict.parent.mkdir(exist_ok=True)
                        if conflict.exists():
                            raise IntegrityFailure("Original-artifact restore conflict archive already exists")
                        destination.rename(conflict)
                        result["cleanup_errors"].append(f"Unexpected destination preserved at {conflict}")
                    source.rename(destination)
                    item["restored_exactly"] = sha(destination) == item["sha256"]
                    if not item["restored_exactly"]:
                        raise IntegrityFailure("Restored original artifact digest differs")
                except BaseException as error:
                    result["cleanup_errors"].append(f"restore original {item['original']}: {error}")
            result["restored_files"] = {}
            for key, path in targets.items():
                try:
                    path = within(path, fixture)
                    original = originals[key]
                    if key == "observer-workstation-screenshot" and path.is_file():
                        shutil.copy2(path, within(output / "background-workstation-native.png", output))
                    if path.exists() and (original is None or path.read_bytes() != original):
                        after = within(output / "after-before-restore" / (key + ".bin"), output)
                        after.parent.mkdir(exist_ok=True)
                        after.write_bytes(path.read_bytes())
                    if original is None:
                        if path.exists():
                            archive = within(output / "created-files-removed" / (key + ".bin"), output)
                            archive.parent.mkdir(exist_ok=True)
                            path.rename(archive)  # Original absence restored; generated bytes preserved, never glob-deleted.
                    elif not path.is_file() or path.read_bytes() != original:
                        temporary = within(path.parent / (".v0110-restore-" + uuid.uuid4().hex + ".tmp"), fixture)
                        temporary.write_bytes(original)
                        os.replace(temporary, path)
                    exact = path.is_file() and path.read_bytes() == original if original is not None else not path.exists()
                    result["restored_files"][key] = {"original_existed": original is not None, "restored_exactly": exact}
                    if not exact:
                        raise IntegrityFailure("Exact original bytes/absence were not restored")
                except BaseException as error:
                    result["cleanup_errors"].append(f"restore {key}: {error}")
            result["addonsCleanup"] = all(item.get("removed_from_fixture") for item in result["installed"]
                                           if item["kind"] == "addon") and all(
                                               item.get("restored_exactly") for item in result["moved_originals"])
            for name, expected in preserved_addons.items():
                try:
                    if sha(within(name, fixture, exists=True)) != expected:
                        raise IntegrityFailure("Preserved v016 addon changed")
                except Exception as error:
                    result["cleanup_errors"].append(f"preserved addon {name}: {error}")
        if result["cleanup_errors"]:
            result["status"] = "FAIL"
        if any(item.get("forced_stop") for item in result["processes"].values()) and result["status"] == "PASS":
            result["status"] = "INCONCLUSIVE"
            result["failure"] = "Forced stop cannot satisfy natural-terminal success"
        result["world_exists_after"] = world.exists()
        product_limits, memory_failures = [], []
        for role in ("server", "client"):
            log = output / (role + ".log")
            if log.is_file():
                for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
                    if re.search(r"snapshot byte budget|snapshot NBT length|exceeds byte limit|鐡掑懓绻僜s*8\s*MiB|鐡掑懓绻僜s*64\s*MiB", line, re.I):
                        if len(product_limits) < 32:
                            product_limits.append({"role": role, "line": line[:2048]})
                    if "OutOfMemoryError" in line and len(memory_failures) < 16:
                        memory_failures.append({"role": role, "line": line[:2048]})
        result["observed_product_limit_log_lines"] = product_limits
        result["observed_jvm_memory_failure_log_lines"] = memory_failures
        if result["status"] != "PASS" and (product_limits or memory_failures):
            result["status"] = "FAIL"
            result["native_scale_outcome"] = "real product byte limit observed" if product_limits else "real JVM memory failure under fixed selected heaps"
        result["native_scale_success_claimed"] = result["status"] == "PASS"
        result["closed_unix"] = time.time()
        save(output / "addons-cleanup.json", {key: result[key] for key in ("installed", "moved_originals", "addonsCleanup", "cleanup_errors")})
        publish()
        artifacts = {str(path.relative_to(output)): sha(within(path, output, exists=True))
                     for path in sorted(output.rglob("*")) if path.is_file()
                     and path.name not in ("artifact-hashes.json", "process-receipt.json")}
        save(output / "artifact-hashes.json", artifacts)
        result["artifact_hash_manifest_sha256"] = sha(output / "artifact-hashes.json")
        save(output / "process-receipt.json", result)
    print(json.dumps({key: result.get(key) for key in ("name", "status", "failure", "processClosed", "addonsCleanup", "output")}, ensure_ascii=False), flush=True)
    return 0 if result["status"] == "PASS" else 1


def latency_flags(args, role):
    return [f"-Dgtmb.latency.{role}=true", f"-Dgtmb.latency.cells={args.cells}",
            f"-Dgtmb.latency.phase={args.phase}", "-Dgtmb.v0110.ctmClient=false",
            "-Dgtmb.v0110.downloadClient=false"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--addon", type=Path, required=True)
    parser.add_argument("--phase", choices=("import",), default="import")
    parser.add_argument("--cells", type=int, choices=(50000, 100000), default=50000)
    parser.add_argument("--timeout-seconds", type=int, default=2700)
    parser.add_argument("--expect-product-sha256", required=True,
                        help="Explicit version binding, checked before any fixture mutation")
    return run(parser.parse_args())


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"run_native: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
