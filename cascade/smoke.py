"""Exercise first install and preservation after reinstall on a disposable Android emulator."""
import argparse
import subprocess
from pathlib import Path


def adb(*args):
    result = subprocess.run(["adb", *map(str, args)], text=True, capture_output=True, check=True)
    print(result.stdout)
    return result.stdout


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    app = next(args.directory.rglob("*x86_64*.apk"))
    tests = next(args.directory.rglob("*androidTest*.apk"))
    package = "net.kinashy.cascadevpn"
    adb("install", "-r", app)
    adb("install", "-r", tests)
    for method in ("seedUserData", "verifyAfterReinstall", "seedLegacyDnsForUpgrade", "verifyAfterDnsUpgrade"):
        if method in ("verifyAfterReinstall", "verifyAfterDnsUpgrade"):
            adb("shell", "am", "force-stop", package)
            adb("install", "-r", app)
        result = adb("shell", "am", "instrument", "-w", "-e", "class",
                     "com.v2ray.ang.handler.CascadeBootstrapTest#" + method,
                     package + ".test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in result or "FAILURES" in result or "INSTRUMENTATION_FAILED" in result:
            raise RuntimeError("Android preservation/repair check failed")
