import os
import subprocess
import sys
import re
import shutil
import signal
import tempfile
import time
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"

# The block that ends a client an earlier session left running, lifted out of the script so it
# can be exercised on its own. Everything between the two markers is the real code.
START = "    # A client left by a session that ended without reaching its own shutdown"
END = '    unset _stale _f\n'


def stray_block():
    text = SESSION.read_text()
    start = text.index(START)
    end = text.index(END, start) + len(END)
    return text[start:end]


class StraySteamTest(unittest.TestCase):
    """A client that outlived its session still owns the singletons: the launch below finds it,
    forwards its arguments to it and exits, so the session ends before anything is on screen."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.steam = self.root / "steam"
        self.steam.mkdir()
        (self.steam / "config/htmlcache").mkdir(parents=True)
        self.fake = self.root / "fake-steam-webhelper"
        self.spawned = []

    def start_a_stray(self):
        # The block finds its victim by argv0 in /proc/*/cmdline, so the process has to actually
        # run as steamrtarm64/steam and stay that pid. A wrapper script would be replaced by its
        # own last command and the pgrep would find nothing to kill.
        holder = self.root / "steamrtarm64"
        holder.mkdir()
        argv0 = str(holder / "steam")
        pid = os.fork()
        if pid == 0:
            try:
                os.execv(sys.executable, [argv0, "-c", "import time; time.sleep(30)"])
            except Exception:
                os._exit(1)
        self.spawned.append(pid)
        for _ in range(50):
            try:
                os.kill(pid, 0)
                break
            except OSError:
                time.sleep(0.05)
        return pid

    def alive(self, pid):
        # a killed child that nothing has reaped still answers signal 0; read its state instead
        try:
            return Path("/proc/%d/stat" % pid).read_text().split(")")[1].split()[0] != "Z"
        except OSError:
            return False

    def tearDown(self):
        for pid in self.spawned:
            try:
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
            except OSError:
                pass

    def run_block(self):
        """Run the block from a file, so the harness's own argv carries none of the pattern the
        block greps for and it does not kill the very shell that is running it."""
        import subprocess

        runner = self.root / "run.sh"
        runner.write_text("set -u\nsteam_root=%s\n%s\n" % (_quote(str(self.steam)), stray_block()))
        runner.chmod(0o755)
        return subprocess.run(["bash", str(runner)], capture_output=True, text=True, timeout=60)

    def test_the_block_ends_a_client_and_removes_what_it_left(self):
        pid = self.start_a_stray()
        self.assertTrue(self.alive(pid), "the stray client should be running before the block")
        matched = subprocess.run(["pgrep", "-f", "steamrtarm64/steam"], capture_output=True, text=True)
        self.assertIn(str(pid), matched.stdout.split(), "the block's pgrep must actually find it")

        lock = self.steam / "config/htmlcache/SingletonLock"
        lock.write_text("x")

        # reap as soon as it dies, so it does not sit in /proc as a zombie the block would wait on
        reaper = subprocess.Popen(["python3", "-c", "import os,time\nwhile time.time()<%f:\n time.sleep(0.1)\n try: os.waitpid(%d, 0); break\n except OSError: pass" % (time.time() + 60, pid)])
        self.addCleanup(reaper.kill)

        result = self.run_block()

        self.assertIn("ending a client an earlier session left running", result.stdout)
        self.assertFalse(self.alive(pid), "the stray client should be gone after the block")
        self.assertFalse(lock.exists(), "the singleton should be removed once the client is gone")

    def test_an_exited_client_does_not_delay_the_start(self):
        # a killed process nobody reaped still reads as running to pgrep; waiting on it would put
        # the full five second pause in front of every session start
        pid = self.start_a_stray()
        os.kill(pid, signal.SIGKILL)
        for _ in range(50):
            try:
                state = Path("/proc/%d/stat" % pid).read_text().split(")")[1].split()[0]
            except OSError:
                break
            if state == "Z":
                break
            time.sleep(0.05)
        self.assertEqual("Z", state, "the test needs a reaped-nobody zombie")

        began = time.monotonic()
        result = self.run_block()
        elapsed = time.monotonic() - began

        self.assertNotIn("ending a client", result.stdout)
        self.assertLess(elapsed, 3.0, "a zombie client should not make the start wait")

    def test_a_clean_start_kills_nothing_and_says_nothing(self):
        result = self.run_block()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("ending a client", result.stdout)
        self.assertNotIn("cleared what an earlier session left", result.stdout)

    def test_the_stale_sweep_runs_even_when_a_client_is_found(self):
        # the old code skipped the whole block when pgrep matched; the sweep must not depend on it
        text = SESSION.read_text()
        block = stray_block()
        self.assertIn("cleared what an earlier session left", block)
        self.assertLess(block.index("_bl_stray"), block.index("_stale"),
                        "the stale sweep must come after, not be conditional on, the process check")


def _quote(text):
    return "'" + text.replace("'", "'\\''") + "'"


if __name__ == "__main__":
    unittest.main()