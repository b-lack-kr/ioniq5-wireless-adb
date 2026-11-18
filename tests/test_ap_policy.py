import subprocess,tempfile,unittest
from pathlib import Path
POLICY=Path(__file__).resolve().parents[1]/'package/runtime/ap-policy.sh'
class Policy(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root=Path(self.tmp.name)
  self.mod=self.root/'data/hmgapi/mods/neev-usb-carplay'; self.status=self.root/'data/hmgapi/state/services.status'
 def claims(self):
  return subprocess.run(['sh','-c','. "$1"; carplay_claims_radio "$2"','sh',str(POLICY),str(self.root)]).returncode==0
 def test_absent(self): self.assertFalse(self.claims())
 def test_enabled(self): self.mod.mkdir(parents=True); self.assertTrue(self.claims())
 def test_disabled_without_supervisor_state_is_conservative(self):
  self.mod.mkdir(parents=True); (self.mod/'.disabled').touch(); self.assertTrue(self.claims())
 def test_disabled_but_service_running(self):
  self.mod.mkdir(parents=True); (self.mod/'.disabled').touch(); self.status.parent.mkdir(parents=True); self.status.write_text('neev-usb-carplay usb-ap running 100 0\n'); self.assertTrue(self.claims())
 def test_disabled_and_service_gone(self):
  self.mod.mkdir(parents=True); (self.mod/'.disabled').touch(); self.status.parent.mkdir(parents=True); self.status.write_text('another service running 101 0\n'); self.assertFalse(self.claims())
 def test_disabled_and_service_stopped(self):
  self.mod.mkdir(parents=True); (self.mod/'.disabled').touch(); self.status.parent.mkdir(parents=True); self.status.write_text('neev-usb-carplay usb-ap stopped 0 0\n'); self.assertFalse(self.claims())
 def test_symlink_is_conservative(self):
  self.mod.parent.mkdir(parents=True); self.mod.symlink_to(self.root/'missing'); self.assertTrue(self.claims())
