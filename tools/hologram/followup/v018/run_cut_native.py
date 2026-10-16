"""ROOT-only paired cut launcher for an existing isolated packaged fixture; never copies a world."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

import psutil

ROOT = Path(__file__).resolve().parents[4]
WORLD_PREFIX = "hologram-cut-v020-"
CLIENT_RSS_LIMIT = int(4.4 * 1024 ** 3)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, record):
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def contained(path, parent=ROOT):
    path = path.resolve(strict=True)
    path.relative_to(parent)
    return path


def assert_idle(fixture):
    prefix = str(fixture).replace("\\", "/").casefold().rstrip("/")
    for process in psutil.process_iter(["pid", "name"]):
        if process.info["name"].lower() not in ("java.exe", "javaw.exe", "java"):
            continue
        try:
            cwd = process.cwd().replace("\\", "/").casefold().rstrip("/")
            command = " ".join(process.cmdline()).replace("\\", "/").casefold()
        except psutil.NoSuchProcess:
            continue
        if cwd == prefix or cwd.startswith(prefix + "/") or prefix in command:
            raise RuntimeError(f"Fixture still has owned Java PID {process.pid}; no staging performed")


def set_properties(text, changes):
    lines = text.splitlines()
    for key, value in changes.items():
        lines = [line for line in lines if not re.match(re.escape(key) + r"\s*[=:]", line)]
        lines.append(f"{key}={value}")
    return "\n".join(lines) + "\n"


def client_args(text, fixture, receipts):
    # Prepared java-args.txt has exactly one Java argument per quoted line.
    lines = text.splitlines()
    username = next((i for i, line in enumerate(lines) if line.strip('"') == "--username"), None)
    if username is None or username + 1 >= len(lines):
        raise ValueError("Prepared client arguments lack an offline username")
    # Vanilla 1.7.10 login bounds names to sixteen characters; menu-only fixtures never exercise it.
    offline_name = "GTMBProbeV018"
    lines[username + 1] = f'"{offline_name}"'
    identity = next((i for i, line in enumerate(lines) if line.strip('"') == "--uuid"), None)
    if identity is None or identity + 1 >= len(lines):
        raise ValueError("Prepared client arguments lack an offline UUID")
    offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + offline_name).encode("utf-8")).digest(), version=3)
    lines[identity + 1] = f'"{offline_uuid.hex}"'
    properties = {
        "gtmb.v016.productionGui": "false",
        "gtmb.hologram.cutNativeClient": "true",
        "gtmb.hologram.cutNativeAddress": "127.0.0.1:25678",
        "gtmb.hologram.followupRoot": fixture.as_posix(),
        "gtmb.hologram.followupReceiptDir": receipts.as_posix(),
        "gtmb.hologram.nativeStartupSeconds": "600",
    }
    # Exclude previous opt-in test profiles, retaining runtime/library/identity arguments.
    lines = [line for line in lines if not re.match(r"-Xm[sx]", line.strip().strip('"')) and not any(token in line for token in
             ("-Dgtmb.hologram.cutAeData", "-Dgtmb.hologram.cutNative", "-Dgtmb.hologram.followup",
              "-Dgtmb.hologram.nativeStartupSeconds", "-Dgtmb.v016.productionGui"))]
    additions = ['"-Xms768M"', '"-Xmx3072M"'] + [f'"-D{key}={value}"' for key, value in properties.items()]
    index = next((i for i, line in enumerate(lines) if line.strip('"') == "-cp"), None)
    if index is None:
        raise ValueError("Prepared client arguments have no explicit -cp boundary")
    return "\n".join(lines[:index] + additions + lines[index:]) + "\n"


def stage(args):
    fixture, jar = contained(args.fixture), contained(args.jar)
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", args.name):
        raise ValueError("Name must be a bounded ASCII run identifier")
    assert_idle(fixture)  # Must precede every fixture/output mutation.
    receipts = contained(fixture / "receipts", fixture)
    if (receipts / "cut-os-input-bridge.lock").exists():
        raise RuntimeError("An input bridge lock remains; inspect it before staging")
    environment = fixture / "environment.json"
    env = json.loads(environment.read_text(encoding="utf-8"))
    java = Path(env["java"]).resolve(strict=True)
    client, server = contained(fixture / "client", fixture), contained(fixture / "server", fixture)
    world = WORLD_PREFIX + args.name
    if (server / world).exists():
        raise RuntimeError("Selected world already exists; choose a new name")
    selected_hash = digest(jar)
    output = ROOT / "build/verification/mui2" / args.name
    manifest = fixture / "cut-native-stage.json"
    previous = json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else None
    if previous and previous["name"] == args.name:
        if previous["normal_sha256"] != selected_hash or (output / "process-receipt.json").exists():
            raise RuntimeError("This name was already used or selected JAR changed")
        if any(receipts.glob("cut-*")):
            raise RuntimeError("Staged receipts are no longer fresh")
        return previous
    old_receipts = list(receipts.glob("cut-*"))
    if old_receipts and not previous:
        raise RuntimeError("Old cut receipts lack a previous-run manifest; archive them explicitly first")
    if output.exists():
        raise FileExistsError("Run output already exists; choose a fresh name")
    previous_jars = [client / "mods" / env["normal_jar"], server / "mods" / env["normal_jar"]]
    destinations = [client / "mods" / jar.name, server / "mods" / jar.name]
    for path in previous_jars:
        contained(path, fixture)
    for path in destinations:
        contained(path.parent, fixture)
        if path.exists():
            contained(path, fixture)
    base_args = (fixture / "java-args.txt").read_text(encoding="utf-8")
    generated_args = client_args(base_args, fixture, receipts)
    properties_path = server / "server.properties"
    properties = set_properties(properties_path.read_text(encoding="latin-1"),
                                {"level-name": world, "server-ip": "127.0.0.1", "server-port": 25678,
                                 "online-mode": "false", "max-players": 1})
    output.mkdir(parents=True)
    before = output / "before-staging"
    before.mkdir()
    for path in [environment, fixture / "server-environment.json", properties_path]:
        if path.is_file():
            shutil.copy2(path, before / path.name)
    save(before / "previous-normal.json", {
        side: {"path": str(path), "sha256": digest(path)}
        for side, path in zip(("client", "server"), previous_jars)})
    if previous:
        archive = ROOT / "build/verification/mui2" / previous["name"] / "receipts"
        archive.mkdir(parents=True, exist_ok=True)
        for path in old_receipts:
            target = archive / path.name
            if target.exists():
                raise FileExistsError(f"Never replace old receipt: {target}")
            path.rename(target)
    assert_idle(fixture)
    for path in destinations:
        shutil.copy2(jar, path)
        if digest(path) != selected_hash:
            raise RuntimeError("Copied normal JAR hash differs")
    for old, selected in zip(previous_jars, destinations):
        if old != selected:
            contained(old, fixture).unlink()
    env.update(normal_jar=jar.name, normal_sha256=selected_hash, cut_native_name=args.name)
    save(environment, env)
    server_env_path = fixture / "server-environment.json"
    server_env = json.loads(server_env_path.read_text(encoding="utf-8")) if server_env_path.is_file() else {
        "fixture": str(server), "scope": "Existing isolated packaged server; fresh named native cut world"}
    server_env.update(normal_jar=env["normal_jar"], normal_sha256=selected_hash, world=world)
    save(server_env_path, server_env)
    properties_path.write_text(properties, encoding="latin-1")
    args_file = fixture / "cut-java-args.txt"
    args_file.write_text(generated_args, encoding="utf-8")
    common = ["-Dgtmb.hologram.nativeStartupSeconds=600", f"-Dgtmb.hologram.followupRoot={fixture.as_posix()}",
              f"-Dgtmb.hologram.followupReceiptDir={receipts.as_posix()}"]
    record = dict(name=args.name, fixture=str(fixture), receipts=str(receipts), output=str(output), world=world,
                  normal_source=str(jar), normal_sha256=selected_hash, status="STAGED_ONLY_NOT_RUN",
                  client_jar=str(destinations[0]), server_jar=str(destinations[1]), staged_unix=time.time(),
                  client_command=[str(java), "@" + str(args_file)],
                  server_command=[str(java), "-Xms1G", "-Xmx6G", "-Dhttp.proxyHost=127.0.0.1",
                                  "-Dhttp.proxyPort=7890", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
                                  "-Dgtmb.hologram.cutNativeServer=true", *common,
                                  "@java9args.txt", "-jar", "lwjgl3ify-forgePatches.jar", "nogui"],
                  java_sha256=digest(java), client_args_sha256=digest(args_file))
    save(manifest, record)
    save(output / "stage.json", record)
    return record


def run(record, timeout):
    fixture, receipts, output = map(Path, (record["fixture"], record["receipts"], record["output"]))
    assert_idle(fixture)
    processes, logs = {}, []
    result = dict(record, status="FAIL", scope="Actual paired normal-JAR dedicated server/client plus targeted Win32 input; listed cut probes only",
                  started_unix=time.time(), timeout_seconds=timeout, processes={}, failure="",
                  client_heap_max="3072M", client_rss_limit_bytes=CLIENT_RSS_LIMIT, client_peak_rss_bytes=0,
                  client_rss_samples=0, resource_stop_reason="", product_verdict="NOT_EVALUATED")
    resource_limited = False
    deadline = time.monotonic() + timeout

    def launch(role, command, cwd):
        log = (output / f"{role}.log").open("xb")
        logs.append(log)
        process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                   stdin=subprocess.PIPE if role == "server" else subprocess.DEVNULL,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        processes[role] = process
        result["processes"][role] = {"pid": process.pid, "command": command, "cwd": str(cwd),
                                      "started_unix": time.time(), "forced_stop": False}
        save(output / "runtime.json", result)
        return process

    def failures():
        for name in ("cut-client-fail.txt", "cut-server-fail.txt"):
            if (receipts / name).exists():
                raise RuntimeError(name + ": " + (receipts / name).read_text(encoding="utf-8", errors="replace"))

    try:
        server = launch("server", record["server_command"], fixture / "server")
        while not re.search(r"Done \([0-9.,]+s\)!", (output / "server.log").read_text(encoding="utf-8", errors="replace")):
            failures()
            if server.poll() is not None:
                raise RuntimeError("Server exited before Done")
            if time.monotonic() >= deadline:
                raise TimeoutError("Server startup deadline expired")
            time.sleep(.5)
        client = launch("client", record["client_command"], fixture / "client")
        client_monitor = psutil.Process(client.pid)
        client_created = client_monitor.create_time()
        bridge_command = [sys.executable, str(Path(__file__).with_name("native_input.py")), "--fixture", str(fixture),
                          "--receipts", str(receipts), "--pid", str(client.pid), "--timeout", str(timeout)]
        launch("input", bridge_command, ROOT)
        while time.monotonic() < deadline:
            if client.poll() is None:
                try:
                    if client_monitor.create_time() != client_created:
                        raise RuntimeError("Owned client PID identity changed during RSS monitoring")
                    rss = client_monitor.memory_info().rss
                    result["client_rss_samples"] += 1
                    result["client_peak_rss_bytes"] = max(result["client_peak_rss_bytes"], rss)
                    if rss >= CLIENT_RSS_LIMIT:
                        resource_limited = True
                        result["status"] = "RESOURCE_LIMIT_STOPPED_NOT_PRODUCT_RESULT"
                        result["resource_stop_reason"] = f"Owned client RSS {rss} reached {CLIENT_RSS_LIMIT} bytes"
                        result["resource_limit_detected_unix"] = time.time()
                        result["processes"]["client"]["forced_stop"] = True
                        # Stop client growth before bridge input cleanup or server shutdown waits.
                        client.terminate()
                        result["client_termination_requested_unix"] = time.time()
                        raise RuntimeError(result["resource_stop_reason"])
                except psutil.NoSuchProcess:
                    pass
            failures()
            passes = all((receipts / name).is_file() and (receipts / name).read_text(encoding="utf-8").startswith("PASS ")
                         for name in ("cut-client-pass.txt", "cut-server-pass.txt"))
            exits = {role: process.poll() for role, process in processes.items()}
            if any(code not in (None, 0) for code in exits.values()):
                raise RuntimeError(f"Owned process failed: {exits}")
            if all(code is not None for code in exits.values()):
                if not passes:
                    raise RuntimeError("Processes closed without both real PASS markers")
                if any(digest(Path(record[key])) != record["normal_sha256"] for key in ("client_jar", "server_jar")):
                    raise RuntimeError("Normal JAR changed during runtime")
                result["status"] = "PASS_FOR_LISTED_CUT_NATIVE_SCOPE"
                result["product_verdict"] = "PASS_FOR_LISTED_CUT_NATIVE_SCOPE"
                break
            if client.poll() is not None and not (receipts / "cut-client-pass.txt").exists():
                raise RuntimeError("Client exited before its native PASS marker")
            if server.poll() is not None and not (receipts / "cut-server-pass.txt").exists():
                raise RuntimeError("Server exited before its independent PASS marker")
            time.sleep(.05)
        else:
            raise TimeoutError("Paired run did not naturally close within timeout")
    except BaseException as error:
        result["failure"] = f"{type(error).__name__}: {error}"
    finally:
        if not result["status"].startswith("PASS_"):
            # Ask the bridge to release its own held keys before any forced Python stop.
            if "input" in processes and processes["input"].poll() is None:
                (receipts / "cut-os-input-bridge-stop.txt").write_text("owned launcher shutdown\n", encoding="ascii")
                result["input_release_requested_unix"] = time.time()
                result["input_release_after_client_resource_stop"] = resource_limited
            if "server" in processes and processes["server"].poll() is None:
                try:
                    processes["server"].stdin.write(b"stop\n")
                    processes["server"].stdin.flush()
                except OSError:
                    pass
            for role in ("input", "server", "client"):
                process = processes.get(role)
                if process is None or process.poll() is not None:
                    continue
                try:
                    process.wait(timeout=1 if resource_limited and role == "client" else 10 if role == "input" else 20)
                except subprocess.TimeoutExpired:
                    result["processes"][role]["forced_stop"] = True
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
        for role, process in processes.items():
            result["processes"][role].update(exit_code=process.poll(), closed=process.poll() is not None)
        for log in logs:
            log.close()
        result["closed_unix"] = time.time()
        result["artifacts_sha256"] = {path.name: digest(path) for path in output.glob("*.log")}
        result["receipt_sha256"] = {path.name: digest(path) for path in receipts.glob("cut-*") if path.is_file()}
        save(output / "process-receipt.json", result)
    print(json.dumps({key: result[key] for key in ("status", "name", "output", "failure", "processes")}, indent=2))
    return 0 if result["status"].startswith("PASS_") else 2 if resource_limited else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--timeout", type=float, default=1200)
    parser.add_argument("--stage-only", action="store_true")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("Timeout must be positive")
    record = stage(args)
    if args.stage_only:
        print(json.dumps(record, indent=2))
        return 0
    return run(record, args.timeout)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"run_cut_native: {error}", file=sys.stderr)
        sys.exit(1)
