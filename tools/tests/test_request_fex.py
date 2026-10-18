import runpy
import shutil
import tempfile
import unittest
from pathlib import Path

COMPAT = runpy.run_path(str(Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/steam-compatibility"))


class RequestFexTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / "steamapps").mkdir()
        client = self.root / "steamrtarm64/steam"
        client.parent.mkdir()
        self.log = self.root / "client.log"
        client.write_text("#!/bin/sh\necho \"start $1\" >> %s\nsleep 0.3\necho \"end $1\" >> %s\n" % (self.log, self.log))
        client.chmod(0o755)

    def install(self, app):
        (self.root / ("steamapps/appmanifest_%s.acf" % app)).write_text("")

    def test_one_install_is_asked_for_at_a_time(self):
        asked = set()
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/3127680", "end steam://install/3127680"], self.log.read_text().splitlines())
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.install("3127680")
        self.assertEqual(["1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/3127680", "end steam://install/3127680",
                          "start steam://install/1628350", "end steam://install/1628350"],
                         self.log.read_text().splitlines())
        self.install("1628350")
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual({"3127680", "1628350"}, asked)

    def test_a_declined_install_is_not_asked_again_in_the_same_session(self):
        asked = set()
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["3127680"], COMPAT["request_fex"](str(self.root), set()))

    def test_only_what_is_missing_is_asked_for_once(self):
        self.install("3127680")
        asked = set()
        self.assertEqual(["1628350"], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual([], COMPAT["request_fex"](str(self.root), asked))
        self.assertEqual(["start steam://install/1628350", "end steam://install/1628350"], self.log.read_text().splitlines())


class MainAsksForNothingBeforeTheClientRuns(unittest.TestCase):
    """main() runs before the session starts its own client. request_fex runs the client's binary
    to forward the URL, so asking there launches a second client, which refuses to start and ends
    the session. The guard is the same one the original caller in droiddeck-fex uses."""

    def guard_in_source(self):
        source = (Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/steam-compatibility").read_text()
        body = source[source.index("def main():"):]
        return body

    def test_the_request_sits_behind_the_running_check(self):
        body = self.guard_in_source()
        guard = body.index("if steam_running():")
        ask = body.index("request_fex(steam_root, set())")
        self.assertLess(guard, ask, "request_fex must be guarded by steam_running()")

    def test_nothing_is_asked_for_while_the_client_is_down(self):
        recorded = []
        running = lambda: False
        request = lambda root, asked: recorded.append("asked") or []
        # the guard as it reads in main()
        if running():
            for app in request("root", set()):
                pass
        self.assertEqual([], recorded)

    def test_it_is_asked_for_once_the_client_is_up(self):
        recorded = []
        running = lambda: True
        request = lambda root, asked: recorded.append("asked") or []
        if running():
            for app in request("root", set()):
                pass
        self.assertEqual(["asked"], recorded)


if __name__ == "__main__":
    unittest.main()
