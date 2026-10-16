import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('manage',Path(__file__).with_name('manage.py'))
manage=importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)
class DeploymentTests(unittest.TestCase):
 def test_credentials_are_private_and_stable(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);path=manage.initialize(root);content=path.read_text()
   self.assertEqual(path.stat().st_mode & 0o777,0o600)
   self.assertIn('QUANTUM_ALLOW_LIVE=false',content)
   self.assertIn('QUANTUM_TEST_MODE=false',content)
   self.assertEqual(manage.initialize(root).read_text(),content)
 def test_ci_must_match_exact_main_push_commit(self):
  result={'workflow_runs':[{'head_sha':'good','event':'push','head_branch':'main','status':'completed','conclusion':'success'}]}
  with patch.object(manage,'github',return_value=result):
   self.assertTrue(manage.verified('owner/repo','good',''))
   self.assertFalse(manage.verified('owner/repo','other',''))
  result['workflow_runs'][0]['event']='pull_request'
  with patch.object(manage,'github',return_value=result):self.assertFalse(manage.verified('owner/repo','good',''))
if __name__=='__main__':unittest.main()
