"""ROOT-only placement-compatibility helper contracts; legacy full suite and 64k business are not covered."""

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
import zipfile

sys.dont_write_bytecode = True
import run_composite as common

ROOT, BUILD, FIXTURE, OUTPUTS = common.ROOT, common.BUILD, common.FIXTURE, common.OUTPUTS
LEAF = "native-contracts.properties"
SUITE = "placement-compatibility"
HELPERS = "detachedReportingParts,idleAeHosts,basicMachineFacingRestore"
SCOPE = "Three existing placement compatibility native helpers with actual Forge FakePlayer; legacy full suite and real-Netty 64k business are not covered"


def atomic_replace(source, target):
    for attempt in range(8):
        try:
            os.replace(source, target)
            return
        except PermissionError:
            if os.name != "nt" or attempt == 7:
                raise
            time.sleep(0.025)


def save(path, value):
    path = common.within(path, OUTPUTS)
    temporary = common.within(path.parent / ("." + path.name + "." + uuid.uuid4().hex + ".tmp"), OUTPUTS)
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    atomic_replace(temporary, path)


def native_properties(output):
    """Admit one new leaf locally; keep run_composite's fixed business-receipt allowlist unchanged."""
    parent = common.within(output, OUTPUTS, exists=True)
    if parent.parent != OUTPUTS or not re.fullmatch(r"[A-Za-z0-9_-]{1,20}", parent.name):
        raise ValueError("Native contracts receipt needs a direct owned case directory")
    named = parent / LEAF
    for attempt in range(common.RECEIPT_READ_ATTEMPTS):
        try:
            common.receipt_target(named.resolve(strict=False), named)
            data = common._read_shared_receipt(named).decode("latin-1")
            break
        except common._ReceiptReplaced:
            if attempt + 1 == common.RECEIPT_READ_ATTEMPTS:
                raise RuntimeError("Native contracts receipt remained delete-pending")
            time.sleep(0.025)
    result = {}
    for line in data.splitlines():
        if not line or line.lstrip().startswith(("#", "!")):
            continue
        match = re.match(r"((?:\\.|[^=:\s])+)[=:](.*)", line)
        if match:
            value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda item: chr(int(item[1], 16)), match[2])
            result[match[1]] = re.sub(r"\\(.)", lambda item: {"n": "\n", "r": "\r", "t": "\t"}.get(item[1], item[1]), value)
    return result


def hashes(directory, boundary, suffix=None):
    directory = common.within(directory, boundary, exists=True)
    return {path.relative_to(directory).as_posix(): common.sha(common.within(path, boundary, exists=True))
            for path in sorted(directory.rglob("*")) if path.is_file()
            and (suffix is None or path.suffix.casefold() == suffix)}


def server_properties(original, name):
    changes = {"level-name": name, "level-type": "FLAT", "generator-settings": "2;0;1;",
               "level-seed": "111", "generate-structures": "false", "server-ip": "127.0.0.1",
               "server-port": str(common.PORT), "online-mode": "false", "max-players": "0",
               "gamemode": "0", "force-gamemode": "true", "spawn-protection": "0",
               "allow-nether": "true", "allow-flight": "false", "view-distance": "3",
               "enable-rcon": "false", "enable-query": "false", "max-tick-time": "-1"}
    lines = original.decode("latin-1").splitlines()
    for key, value in changes.items():
        lines = [line for line in lines if not re.match(r"\s*" + re.escape(key) + r"\s*[=:]", line)]
        lines.append(key + "=" + value)
    return ("\n".join(lines) + "\n").encode("latin-1")


def arguments(output, world_name, product_sha, addon_sha):
    prepared = common.argfile_tokens(common.within(FIXTURE / "server/java9args.txt", FIXTURE, exists=True).read_text(encoding="utf-8"))
    if any(value in ("-jar", "-cp", "-classpath") for value in prepared):
        raise ValueError("Prepared server/java9args.txt must contain JVM/bootstrap options only")
    tokens = [value for value in prepared if not value.startswith("-Dgtmb.") and not re.match(r"-Xm[sx]", value)]
    tokens += ["-Xms1G", "-Xmx6G", *common.common_flags(output, False, 0),
               "-Dgtmb.hologram.smoketest=false", "-Dgtmb.hologram.extendedCompatibilitySmoke=false",
               "-Dgtmb.hologram.backgroundProbe=false", "-Dgtmb.v0111.nativeContractsOnly=true",
               "-Dgtmb.v0111.nativeContractsSuite=" + SUITE,
               "-Dgtmb.v0111.nativeContractsWorld=" + world_name,
               "-Dgtmb.v0111.nativeContractsProductSha256=" + product_sha,
               "-Dgtmb.v0111.nativeContractsAddonSha256=" + addon_sha,
               "-jar", common.within(FIXTURE / "server/lwjgl3ify-forgePatches.jar", FIXTURE, exists=True).as_posix(), "nogui"]
    common.validate_heap(tokens, "-Xmx6G")
    return tokens


def run(args):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,20}", args.name):
        raise ValueError("--name requires 1..20 ASCII letters/digits/underscore/hyphen")
    if not 120 <= args.timeout_seconds <= 3600:
        raise ValueError("--timeout-seconds must be 120..3600")
    fixture = common.within(FIXTURE, BUILD, exists=True)
    if fixture != FIXTURE.absolute() or common.standard_namespace(OUTPUTS.resolve()) != OUTPUTS.absolute():
        raise ValueError("Owned fixture/output roots cannot redirect")
    server = common.within(fixture / "server", fixture, exists=True)
    mods = common.within(server / "mods", server, exists=True)
    product = common.verify_jar(args.jar, "com/miaokatze/gtmb/hologram/HologramNativePlacementSmoke.class")
    addon = common.verify_jar(args.addon, "gtmb/v0111/probe/NativeContractsProbe.class")
    for artifact, expected in ((product, args.expect_product_sha256), (addon, args.expect_addon_sha256)):
        if not re.fullmatch(r"[a-fA-F0-9]{64}", expected) or common.sha(artifact) != expected.lower():
            raise common.IntegrityFailure("Explicit expected artifact SHA-256 differs: " + str(artifact))
    with zipfile.ZipFile(addon) as archive:
        if any(name.endswith(".class") and not name.startswith("gtmb/v0111/probe/") for name in archive.namelist()):
            raise common.IntegrityFailure("Addon may contain only independent test probe classes")
    if product.name.casefold() == addon.name.casefold():
        raise ValueError("Product and addon destination names must differ")
    output = common.within(OUTPUTS / args.name, BUILD)
    world_name = "hologram-smoke-v0111-" + args.name
    world = common.within(server / world_name, server)
    if output.exists() or world.exists():
        raise FileExistsError("Both case directory and named smoke world must be fresh")
    props = common.within(server / "server.properties", server, exists=True)
    original = props.read_bytes()
    testlab = common.within(server / "config/gtmb-testlab.cfg", server, exists=True)
    if re.findall(r"(?m)^\s*B:enabled\s*=\s*(true|false)\s*$", testlab.read_text(encoding="utf-8")) != ["false"]:
        raise common.IntegrityFailure("Existing owned GTMB TestLab must be explicitly disabled; no implicit scenario startup")
    gtswn = common.within(server / "config/gtswn/gtswn.cfg", server, exists=True)
    if re.findall(r"(?m)^\s*B:enableAgentTestHarness\s*=\s*(true|false)\s*$", gtswn.read_text(encoding="utf-8")) != ["false"]:
        raise common.IntegrityFailure("GTSWN agent harness must remain disabled for this single selected suite")
    environment_path = common.within(fixture / "environment.json", fixture, exists=True)
    environment = json.loads(environment_path.read_text(encoding="utf-8"))
    java = common.standard_namespace(Path(environment["java"]).resolve(strict=True))
    if not java.is_file() or java.name.casefold() not in ("java.exe", "java"):
        raise ValueError("Prepared environment must identify a Java executable")
    common.assert_idle()
    common.assert_port_available()
    if common.within(fixture / "receipts/cut-os-input-bridge.lock", fixture).exists():
        raise RuntimeError("Existing owned input-bridge lock requires root inspection")
    output.mkdir(parents=True)
    before = common.within(output / "before", output)
    before.mkdir()
    (before / "server.properties").write_bytes(original)
    argfile = output / "server.args"
    common.write_argfile(argfile, arguments(output, world_name, common.sha(product), common.sha(addon)))
    command = [str(java), "@" + str(argfile)]
    result = {"name": args.name, "status": "RUNNING", "scope": SCOPE, "suite": SUITE,
              "selected_helpers": HELPERS.split(","), "full_suite_invoked": False, "fixture": str(fixture),
              "output": str(output), "world": str(world), "world_name": world_name, "port": common.PORT,
              "server_Xmx": "6G", "client_started": False, "realNetty64kBusinessClaim": False,
              "user_instance_modified": False, "seed_worlds_copied": False, "existing_worlds_deleted": False,
              "normal_source": str(product), "normal_sha256": common.sha(product),
              "addon_source": str(addon), "addon_sha256": common.sha(addon),
              "java": str(java), "java_sha256": common.sha(java), "environment_sha256": common.sha(environment_path),
              "bootstrap_sha256": common.sha(server / "lwjgl3ify-forgePatches.jar"),
              "argument_sha256": common.sha(argfile), "command": command, "cwd": str(server),
              "dependency_hashes_before": hashes(mods, server, ".jar"),
              "config_hashes_before": hashes(server / "config", server),
              "server_properties_original_sha256": hashlib.sha256(original).hexdigest(),
              "source_hashes_before": {"src": hashes(ROOT / "src", ROOT), "tools": hashes(ROOT / "tools", ROOT)},
              "installed": [], "moved_originals": [], "cleanup_errors": [], "processClosed": False,
              "addonsCleanup": False, "forced_stop": False, "started_unix": time.time()}
    process = None
    streams = []
    touched = False

    def publish():
        save(output / "runtime.json", result)

    def record_error(operation, error):
        result["cleanup_errors"].append(operation + ": " + type(error).__name__ + ": " + str(error))

    save(output / "stage.json", result)
    try:
        common.assert_idle()
        common.assert_port_available()
        touched = True
        props.write_bytes(server_properties(original, world_name))
        result["server_properties_staged_sha256"] = common.sha(props)
        for path in sorted(mods.rglob("*.jar")):
            if not path.name.casefold().startswith("gtmb"):
                continue
            source = common.within(path, mods, exists=True)
            staged = common.within(output / "staging-original-server" / source.relative_to(mods), output)
            staged.parent.mkdir(parents=True, exist_ok=True)
            item = {"original": str(source), "staging": str(staged), "sha256": common.sha(source)}
            result["moved_originals"].append(item)
            publish()
            source.rename(staged)
        for artifact, kind in ((product, "normal"), (addon, "addon")):
            destination = common.within(mods / artifact.name, mods)
            item = {"path": str(destination), "kind": kind, "sha256": common.sha(artifact), "created": False}
            result["installed"].append(item)
            with destination.open("xb") as target:
                item["created"] = True
                publish()
                with artifact.open("rb") as source:
                    shutil.copyfileobj(source, target)
            if common.sha(destination) != item["sha256"]:
                raise common.IntegrityFailure("Installed artifact digest differs")
        common.assert_idle()
        common.assert_port_available()
        result["dependency_hashes_staged"] = hashes(mods, server, ".jar")
        stdout = (output / "server.log").open("xb")
        stderr = (output / "server.stderr.log").open("xb")
        streams += [stdout, stderr]
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        result.update(pid=process.pid, hidden_console=os.name == "nt")
        publish()
        deadline = time.monotonic() + args.timeout_seconds
        heartbeat = 0.0
        while process.poll() is None:
            result["native_evidence"] = native_properties(output)
            if (output / "abort.request").exists():
                raise InterruptedError("Root requested graceful stop through abort.request")
            if time.monotonic() >= deadline:
                raise TimeoutError("Native contracts deadline expired; no success inferred")
            if time.monotonic() - heartbeat >= 30:
                heartbeat = time.monotonic()
                publish()
                print(json.dumps({"case": args.name, "pid": process.pid, "native_status": result["native_evidence"].get("status", "STARTING")}), flush=True)
            time.sleep(0.25)
        evidence = native_properties(output)
        result.update(native_evidence=evidence, exit_code=process.returncode)
        if evidence.get("status") == "FAIL":
            result.update(status="FAIL", failure_kind=evidence.get("failureKind", "PRODUCT_OR_ASSERTION"), failure=evidence.get("failure", evidence.get("receiptFailure", "Native suite failed")))
        elif process.returncode == 0 and all(evidence.get(key) == value for key, value in {
                "status": "PASS", "invocationComplete": "true", "shutdownRequested": "true",
                "suite": SUITE, "selectedHelpers": HELPERS, "fullSuiteInvoked": "false",
                "nativeActorGameType": "0", "nativeActorCreative": "false",
                "helper.detachedReportingParts": "PASS", "helper.idleAeHosts": "PASS", "helper.basicMachineFacingRestore": "PASS",
                "noRealPlayersBefore": "true", "noRealPlayersAfter": "true", "worldName": world_name,
                "productSha256": result["normal_sha256"], "addonSha256": result["addon_sha256"],
                "realNetty64kBusinessClaim": "false"}.items()):
            result.update(status="PASS", natural_terminal=True)
        else:
            raise RuntimeError("Server exit lacks the selected suite's complete native terminal receipt")
    except BaseException as error:
        result.update(status="FAIL" if isinstance(error, common.IntegrityFailure) else "INCONCLUSIVE",
                      failure_kind="TEST_INTEGRITY" if isinstance(error, common.IntegrityFailure) else "RUNNER_ENVIRONMENT",
                      failure=type(error).__name__ + ": " + str(error))
    finally:
        if process is not None:
            try:
                if process.poll() is None:
                    try:
                        process.stdin.write(b"stop\n")
                        process.stdin.flush()
                    except OSError:
                        pass
                    try:
                        process.wait(timeout=45)
                    except subprocess.TimeoutExpired:
                        result["forced_stop"] = True
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=10)
                result["exit_code"] = process.poll()
            except BaseException as error:
                record_error("close owned server", error)
        for stream in streams:
            try:
                stream.close()
            except Exception as error:
                record_error("close log", error)
        result["processClosed"] = process is None or process.poll() is not None
        try:
            common.assert_idle()
            common.assert_port_available()
            result["ownedJavaIdleAfter"] = True
        except Exception as error:
            result["ownedJavaIdleAfter"] = False
            record_error("post-run idle/port", error)
        if not result["processClosed"]:
            record_error("restore", RuntimeError("Owned JVM remains alive; no restore underneath it"))
        elif touched:
            for key, directory, suffix in (("config_hashes_after_native", server / "config", None),
                                           ("dependency_hashes_after_native", mods, ".jar")):
                try:
                    result[key] = hashes(directory, server, suffix)
                except Exception as error:
                    record_error("observe " + key, error)
            for item in result["installed"]:
                if not item["created"]:
                    continue
                try:
                    path = common.within(item["path"], mods, exists=True)
                    final_hash = common.sha(path)
                    item["final_sha256"] = final_hash
                    archive = common.within(output / "staged-used-server" / path.name, output)
                    archive.parent.mkdir(exist_ok=True)
                    path.rename(archive)
                    item["archived"] = str(archive)
                    item["removed_from_fixture"] = not path.exists()
                    if final_hash != item["sha256"]:
                        raise common.IntegrityFailure("Installed artifact changed during native run")
                except BaseException as error:
                    record_error("archive installed " + item["path"], error)
            for item in reversed(result["moved_originals"]):
                try:
                    source = common.within(item["staging"], output)
                    destination = common.within(item["original"], mods)
                    if source.exists():
                        if destination.exists():
                            raise common.IntegrityFailure("Unexpected destination; original preserved in staging, no unowned file moved")
                        if common.sha(source) != item["sha256"]:
                            raise common.IntegrityFailure("Staged original digest changed")
                        source.rename(destination)
                    item["restored_exactly"] = destination.is_file() and common.sha(destination) == item["sha256"]
                    if not item["restored_exactly"]:
                        raise common.IntegrityFailure("Original artifact not restored exactly")
                except BaseException as error:
                    record_error("restore original " + item["original"], error)
            try:
                common.within(props, server)
                (output / "server.properties.after-native").write_bytes(props.read_bytes())
                temporary = common.within(props.parent / (".native-contracts-restore-" + uuid.uuid4().hex + ".tmp"), server)
                temporary.write_bytes(original)
                atomic_replace(temporary, props)
                result["server_properties_restored_exactly"] = props.read_bytes() == original
                if not result["server_properties_restored_exactly"]:
                    raise common.IntegrityFailure("Server properties were not restored exactly")
            except BaseException as error:
                record_error("restore server properties", error)
            result["addonsCleanup"] = all(not item["created"] or item.get("removed_from_fixture") for item in result["installed"]) and all(item.get("restored_exactly") for item in result["moved_originals"])
            try:
                result["dependency_hashes_restored"] = hashes(mods, server, ".jar")
                result["dependency_tree_restored_exactly"] = result["dependency_hashes_before"] == result["dependency_hashes_restored"]
                if not result["dependency_tree_restored_exactly"]:
                    raise common.IntegrityFailure("Server mod tree differs after restoration; no unowned files changed by cleanup")
            except Exception as error:
                record_error("verify restored dependency tree", error)
        result["world_exists_after"] = world.exists()
        try:
            result["source_hashes_after"] = {"src": hashes(ROOT / "src", ROOT), "tools": hashes(ROOT / "tools", ROOT)}
            result["source_tree_unchanged"] = result["source_hashes_before"] == result["source_hashes_after"]
        except Exception as error:
            record_error("observe source boundary", error)
        if result["cleanup_errors"]:
            result["status"] = "FAIL"
        if result["forced_stop"] and result["status"] == "PASS":
            result.update(status="INCONCLUSIVE", failure="Forced exit cannot satisfy native terminal success")
        result["closed_unix"] = time.time()
        save(output / "addons-cleanup.json", {key: result[key] for key in ("installed", "moved_originals", "addonsCleanup", "cleanup_errors", "processClosed")})
        publish()
        artifacts = {str(path.relative_to(output)): common.sha(common.within(path, output, exists=True))
                     for path in sorted(output.rglob("*")) if path.is_file()
                     and path.name not in ("artifact-hashes.json", "process-receipt.json")}
        save(output / "artifact-hashes.json", artifacts)
        result["artifact_hash_manifest_sha256"] = common.sha(output / "artifact-hashes.json")
        save(output / "process-receipt.json", result)
    print(json.dumps({key: result.get(key) for key in ("name", "status", "failure", "processClosed", "addonsCleanup", "output")}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--expect-product-sha256", required=True)
    parser.add_argument("--addon", required=True, type=Path)
    parser.add_argument("--expect-addon-sha256", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    args = parser.parse_args()
    raise SystemExit(0 if run(args)["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
