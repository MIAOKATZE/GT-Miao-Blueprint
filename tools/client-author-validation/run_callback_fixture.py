"""Reproduce callback boundaries using an already built, clean source worktree.

This is a diagnostic probe of the current behavior, not a compatibility acceptance test.
It uses synthetic data and never launches Minecraft or creates a player/session.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def run(command, worktree, output):
    result = subprocess.run(
        command, cwd=worktree, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    output.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError("Command failed; see " + str(output))
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worktree", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    worktree = args.worktree.resolve()
    output = args.output.resolve()
    source = Path(__file__).with_name("CallbackThreadFixture.java")
    suffix = ".exe" if os.name == "nt" else ""
    java = args.java_home / "bin" / ("java" + suffix)
    javac = args.java_home / "bin" / ("javac" + suffix)
    for required in (source, java, javac, worktree / "build/classes/java/main",
                     worktree / "build/classes/java/test"):
        if not required.exists():
            parser.error("Missing build input: " + str(required))
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=worktree, text=True).strip():
        parser.error("Use a clean, already built source worktree")
    output.mkdir(parents=True, exist_ok=False)
    init = output / "classpath.gradle"
    init.write_text('''allprojects {
    afterEvaluate {
        tasks.register("printCallbackProbeClasspath") {
            doLast {
                println("GTMB_PROBE_CP=" + tasks.named("runLocalAuthorTest").get().classpath.asPath)
            }
        }
    }
}
''', encoding="utf-8")
    gradle = str(worktree / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    text = run([gradle, "--offline", "--no-daemon", "--no-configuration-cache",
                "--console=plain", "-q", "-I", str(init), "printCallbackProbeClasspath"],
               worktree, output / "classpath.log")
    rows = [line[len("GTMB_PROBE_CP="):] for line in text.splitlines()
            if line.startswith("GTMB_PROBE_CP=")]
    if len(rows) != 1:
        raise RuntimeError("Expected exactly one classpath; see classpath.log")
    classes = output / "classes"
    classes.mkdir()
    run([str(javac), "-encoding", "UTF-8", "-proc:none", "-source", "8", "-target", "8",
         "-cp", rows[0], "-d", str(classes), str(source)], worktree, output / "compile.log")
    runtime = ["-Xmx2g", "-cp", str(classes) + os.pathsep + rows[0],
               "com.miaokatze.gtmb.hologram.CallbackThreadFixture"]
    argfile = output / "runtime.args"
    argfile.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
                                 for value in runtime), encoding="utf-8")
    run([str(java), "@" + str(argfile)], output, output / "fixture.log")
    receipt = {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=worktree, text=True).strip(),
        "fixtureSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "exitCode": 0,
        "scope": "Headless SERVER physical side, controlled caller thread; no live client/admission/placement",
        "acceptancePassed": False,
    }
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
