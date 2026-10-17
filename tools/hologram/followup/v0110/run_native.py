"""ROOT-only v0110 paired native launcher; operates only on the existing owned fixture."""

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


ROOT = Path(__file__).resolve().parents[4]
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
    resolved = Path(path).resolve(strict=exists)
    resolved.relative_to(Path(parent).resolve(strict=True))
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
            cwd = Path(process.cwd()).resolve().as_posix().casefold().rstrip("/")
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


def common_flags(output, large):
    return [
        f"-Dgtmb.hologram.followupRoot={FIXTURE.as_posix()}",
        f"-Dgtmb.v0110.output={output.as_posix()}",
        f"-Dgtmb.v0110.largeEdit={str(large).lower()}",
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


def client_arguments(output, large):
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
    additions = ["-Xms768M", "-Xmx4096M", *common_flags(output, large),
                 "-Dgtmb.v0110.backgroundClient=true", f"-Dgtmb.v0110.address=127.0.0.1:{PORT}",
                 f"-Dgtmb.hologram.backgroundProbe={str(not large).lower()}",
                 f"-Dgtmb.hologram.previewCaptureWitness={str(not large).lower()}",
                 f"-Dgtmb.hologram.backgroundProbeDir={output.as_posix()}"]
    index = tokens.index("-cp")
    tokens[index:index] = additions
    validate_heap(tokens, "-Xmx4096M")
    return tokens


def server_arguments(output, large):
    prepared = argfile_tokens(within(FIXTURE / "server/java9args.txt", FIXTURE, exists=True).read_text(encoding="utf-8"))
    tokens = [value for value in prepared if not value.startswith("-Dgtmb.")
              and not re.match(r"-Xm[sx]", value)]
    tokens += ["-Xms1G", "-Xmx6G", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890",
               "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890", *common_flags(output, large),
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


def shared_bytes(path):
    """Allow the addon's Windows replace/open race without granting false terminal observations."""
    if os.name != "nt":
        try:
            return path.read_bytes()
        except FileNotFoundError:
            return b""
    import msvcrt
    api = ctypes.WinDLL("kernel32", use_last_error=True)
    api.CreateFileW.restype = ctypes.c_void_p
    api.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p,
                               ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
    handle = api.CreateFileW(str(path), 0x80000000, 7, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        code = ctypes.get_last_error()
        if code in (2, 3):
            return b""
        raise ctypes.WinError(code)
    descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    with os.fdopen(descriptor, "rb") as stream:
        return stream.read()


def properties(path):
    text = shared_bytes(within(path, ROOT)).decode("latin-1")
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
    within(BUILD, ROOT, exists=True)
    fixture = within(FIXTURE, BUILD, exists=True)
    if fixture != FIXTURE.absolute():
        raise ValueError("Only the existing owned fixture is permitted")
    if OUTPUTS.resolve() != OUTPUTS.absolute():
        raise ValueError("Case output root must not redirect through a junction/symlink")
    for role in ("client", "server"):
        within(fixture / role / "mods", fixture, exists=True)
    jar = verify_jar(args.jar, "com/miaokatze/gtmb/client/hologram/HologramScreen.class")
    addon = verify_jar(args.addon, "gtmb/v0110/probe/V0110BackgroundSaveProbe.class")
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
    java = Path(environment["java"]).resolve(strict=True)
    if not java.is_file() or java.name.casefold() not in ("java.exe", "java"):
        raise ValueError("Fixture environment must identify an existing Java executable")
    assert_idle()
    assert_port_available()
    if within(fixture / "receipts/cut-os-input-bridge.lock", fixture).exists():
        raise RuntimeError("Existing CUT input bridge lock requires root inspection before this no-OS-input case")
    client_tokens = client_arguments(output, args.large_edit)
    server_tokens = server_arguments(output, args.large_edit)
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
        "name": args.name, "status": "RUNNING", "mode": "large-edit32768" if args.large_edit else "background-save4096",
        "scope": "Actual dedicated Netty and natural rendered MUI stimulus; no OS input; no user instance/world copied",
        "fixture": str(fixture), "output": str(output), "receipts": str(output), "world": str(world),
        "normal_source": str(jar), "normal_sha256": sha(jar), "addon_source": str(addon), "addon_sha256": sha(addon),
        "java": str(java), "java_sha256": sha(java), "environment_sha256": sha(environment_path),
        "client_Xmx": "4096M", "server_Xmx": "6G", "client_peak_rss_bytes": 0,
        "commands": commands, "argfiles_sha256": {"client": sha(client_args), "server": sha(server_args)},
        "port": PORT, "started_unix": time.time(), "timeout_seconds": TIMEOUT_SECONDS,
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
        deadline = time.monotonic() + TIMEOUT_SECONDS
        server = launch("server")
        startup_deadline = min(deadline, time.monotonic() + 600)
        while not re.search(r"Done \([0-9.,]+s\)!", (output / "server.log").read_text(encoding="utf-8", errors="replace")):
            peer = properties(output / "background-server.properties")
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
        while time.monotonic() < deadline:
            if client.poll() is None:
                try:
                    if monitor.create_time() != created:
                        raise IntegrityFailure("Client PID identity changed")
                    result["client_peak_rss_bytes"] = max(result["client_peak_rss_bytes"], monitor.memory_info().rss)
                except psutil.NoSuchProcess:
                    pass
            evidence = properties(output / "background-client.properties")
            peer = properties(output / "background-server.properties")
            result["native_evidence"], result["server_evidence"] = evidence, peer
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
                if args.large_edit:
                    if not (output / "large-local-edit-native.nbt").is_file() or peer.get("actualSourceChestUnchanged") != "true":
                        raise RuntimeError("Large native case lacks its actual delta receipt/source-world check")
                elif not (output / "background-save-native.nbt").is_file() or evidence.get("backgroundObservedPass") != "true":
                    raise RuntimeError("Background case lacks its task-bound production observer result")
                result["status"] = "PASS"
                result["natural_terminal"] = True
                break
            for role, receipt in (("client", evidence), ("server", peer)):
                if processes[role].poll() is not None and receipt.get("status") not in ("PASS", "COMPLETE"):
                    raise RuntimeError(role + " exited before its real terminal receipt")
            time.sleep(0.2)
        else:
            raise TimeoutError("Native case failed to terminate naturally before deadline")
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--addon", type=Path, required=True)
    parser.add_argument("--large-edit", action="store_true")
    return run(parser.parse_args())


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"run_native: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
