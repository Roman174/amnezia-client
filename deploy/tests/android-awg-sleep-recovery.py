#!/usr/bin/env python3
import argparse
import json
import re
import shlex
import subprocess
import threading
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sleep-seconds", type=int, choices=(600, 3600), default=600)
    parser.add_argument("--traffic-package")
    parser.add_argument("--url")
    parser.add_argument("--output", type=Path, default=Path("android-recovery-result.json"))
    args = parser.parse_args()
    if bool(args.traffic_package) != bool(args.url):
        parser.error("--traffic-package and --url must be specified together")
    adb = ["adb", "-s", args.serial]

    def shell(*command):
        return subprocess.check_output(adb + ["shell", " ".join(shlex.quote(part) for part in command)], text=True)

    subprocess.run(adb + ["get-state"], check=True, stdout=subprocess.DEVNULL)
    samples = []
    states = []
    lock = threading.Lock()
    health = re.compile(
        r"TunnelSnapshot\(handshakeMillis=(\d+), receivedBytes=(\d+), "
        r"sentBytes=(\d+), outboundBytes=(\d+), sendErrors=(\d+)\)"
    )
    logger = subprocess.Popen(
        adb + ["logcat", "-T", "1", "-v", "brief", "AmneziaVpnService:V", "*:S"],
        stdout=subprocess.PIPE, text=True,
    )

    def collect():
        for line in logger.stdout:
            match = health.search(line)
            with lock:
                if match:
                    samples.append((time.monotonic(), tuple(map(int, match.groups()))))
                if "Protocol state changed:" in line:
                    states.append((time.monotonic(), line.split("Protocol state changed:")[-1].strip()))

    threading.Thread(target=collect, daemon=True).start()
    result = {}
    try:
        print("Enable app log saving, connect AWG, keep the screen unlocked and disable secure screen lock on the test device.")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            with lock:
                if samples and samples[-1][1][0] > 0:
                    break
            time.sleep(1)
        else:
            raise RuntimeError("No successful handshake telemetry; check log saving and active VPN")
        shell("dumpsys", "battery", "unplug")
        shell("input", "keyevent", "KEYCODE_SLEEP")
        shell("dumpsys", "deviceidle", "force-idle")
        print(f"Holding forced Doze for {args.sleep_seconds} seconds")
        time.sleep(args.sleep_seconds)
        awake = time.monotonic()
        shell("dumpsys", "deviceidle", "unforce")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        if args.url:
            shell("am", "start", "-a", "android.intent.action.VIEW", "-d", args.url, "-p", args.traffic_package)
        time.sleep(120)
        with lock:
            after = [values for timestamp, values in samples if timestamp >= awake]
            after_states = [state for timestamp, state in states if timestamp >= awake]
        if not after:
            raise RuntimeError("No post-wake telemetry")
        progress = any(b[0] > a[0] or b[1] > a[1] for a, b in zip(after, after[1:]))
        recovered = "RECONNECTING" in after_states
        passed = "DISCONNECTED" not in after_states and after[-1][0] > 0
        if args.url:
            passed = passed and progress
        else:
            passed = passed and not recovered
        result = {
            "passed": passed,
            "sleep_seconds": args.sleep_seconds,
            "traffic_requested": bool(args.url),
            "post_wake_states": after_states,
            "post_wake_samples": after,
            "authenticated_progress": progress,
        }
        if not passed:
            raise RuntimeError("Recovery assertion failed; see sanitized result")
        print("PASS: idle remained connected" if not args.url else "PASS: authenticated tunnel progress after wake")
    finally:
        try:
            shell("dumpsys", "deviceidle", "unforce")
            shell("dumpsys", "battery", "reset")
        finally:
            logger.terminate()
            logger.wait(timeout=5)
            args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
