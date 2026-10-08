import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time
import unittest


@unittest.skipUnless(os.name == "posix", "The container display runner requires Linux")
class DisplayRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.runner = pathlib.Path(__file__).with_name("with-display.sh").resolve()
        self.environment = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ["PATH"], TEST_DISPLAY_ROOT=str(self.root))
        self.environment.pop("DISPLAY", None)
        self.environment.pop("PRICES_HEADLESS", None)
        self.executable("Xvfb", """
import json, os, pathlib, signal, sys, time
root = pathlib.Path(os.environ["TEST_DISPLAY_ROOT"])
if os.getenv("TEST_DISPLAY_FAIL") == "true":
    sys.exit(5)
(root / "display.json").write_text(json.dumps(sys.argv[1:]), encoding="utf-8")
def stop(signum, frame):
    (root / "display-stopped").touch()
    (root / "display-ready").unlink(missing_ok=True)
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
(root / "display-ready").touch()
while True:
    time.sleep(0.05)
""")
        self.executable("xdpyinfo", """
import os, pathlib, sys
sys.exit(0 if (pathlib.Path(os.environ["TEST_DISPLAY_ROOT"]) / "display-ready").exists() else 1)
""")
        self.command = self.executable("fixture-command", """
import os, pathlib, signal, sys, time
root = pathlib.Path(os.environ["TEST_DISPLAY_ROOT"])
def stop(signum, frame):
    time.sleep(0.2)
    (root / "command-shutdown").write_text(str((root / "display-ready").exists()), encoding="utf-8")
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
(root / "command-display").write_text(os.getenv("DISPLAY", ""), encoding="utf-8")
if os.getenv("TEST_COMMAND_WAIT") != "true":
    sys.exit(int(os.getenv("TEST_COMMAND_EXIT", "0")))
while True:
    time.sleep(0.05)
""")

    def executable(self, name, source):
        path = self.root / name
        path.write_text("#!/usr/bin/env python3\n" + source, encoding="utf-8")
        path.chmod(0o755)
        return path

    def run_command(self):
        return subprocess.run(["sh", str(self.runner), str(self.command)], env=self.environment,
                              capture_output=True, text=True, timeout=10)

    def test_headless_default_preserves_exit_status_without_starting_a_display(self):
        self.environment["TEST_COMMAND_EXIT"] = "7"
        result = self.run_command()
        self.assertEqual(result.returncode, 7)
        self.assertFalse((self.root / "display.json").exists())
        self.assertEqual((self.root / "command-display").read_text(encoding="utf-8"), "")

    def test_an_existing_display_is_preserved(self):
        self.environment.update(PRICES_HEADLESS="false", DISPLAY=":42")
        self.assertEqual(self.run_command().returncode, 0)
        self.assertFalse((self.root / "display.json").exists())
        self.assertEqual((self.root / "command-display").read_text(encoding="utf-8"), ":42")

    def test_starts_a_local_display_and_cleans_it_after_command_exit(self):
        self.environment.update(PRICES_HEADLESS="false", TEST_COMMAND_EXIT="7")
        result = self.run_command()
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual((self.root / "command-display").read_text(encoding="utf-8"), ":99")
        self.assertEqual(json.loads((self.root / "display.json").read_text(encoding="utf-8")),
                         [":99", "-screen", "0", "1280x720x24", "-nolisten", "tcp"])
        self.assertTrue((self.root / "display-stopped").exists())

    def test_failed_display_does_not_start_the_application(self):
        self.environment.update(PRICES_HEADLESS="false", TEST_DISPLAY_FAIL="true")
        result = self.run_command()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Virtual display failed", result.stderr)
        self.assertFalse((self.root / "command-display").exists())

    def test_sigterm_waits_for_application_shutdown_before_stopping_the_display(self):
        self.environment.update(PRICES_HEADLESS="false", TEST_COMMAND_WAIT="true")
        process = subprocess.Popen(["sh", str(self.runner), str(self.command)], env=self.environment,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not (self.root / "command-display").exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((self.root / "command-display").exists())
            process.send_signal(signal.SIGTERM)
            _, stderr = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, stderr)
            self.assertEqual((self.root / "command-shutdown").read_text(encoding="utf-8"), "True")
            self.assertTrue((self.root / "display-stopped").exists())
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
