import os
import unittest
from unittest.mock import patch
os.environ["OTEL_SDK_DISABLED"] = "true"
os.environ["CREWAI_TELEMETRY_DISABLED"] = "true"
from fastapi.testclient import TestClient
from app import app
from worker import BudgetGateway

BODY = {"runId": "r", "attemptId": "a", "snapshot": {"agents": [{"id": "a", "revision": 1, "name": "Author", "role": "Writer", "instructions": "Summarize"}], "tasks": [{"id": "t", "revision": 1, "name": "Summary", "instructions": "Summarize notes", "agentId": "a"}], "input": "Meeting notes"}, "limits": {"maxCalls": 2, "maxTokens": 10000, "timeoutSeconds": 20}, "mode": "test"}

class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"QUANTUM_RUNTIME_TOKEN": "test-secret", "QUANTUM_TEST_MODE": "true", "QUANTUM_ALLOW_LIVE": "false"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.client = TestClient(app)
        self.headers = {"Authorization": "Bearer test-secret"}

    def test_authentication_fails_closed(self):
        self.assertEqual(self.client.post("/execute", json=BODY).status_code, 401)
        with patch.dict(os.environ, {"QUANTUM_RUNTIME_TOKEN": ""}):
            self.assertEqual(self.client.post("/execute", json=BODY, headers=self.headers).status_code, 401)

    def test_simulated_run_is_explicit(self):
        response = self.client.post("/execute", json=BODY, headers=self.headers)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertTrue(response.json()["simulated"])
        self.assertEqual(response.json()["outputs"][0]["taskId"], "t")
        self.assertEqual(response.json()["usage"]["calls"], 0)

    def test_modes_fail_closed(self):
        with patch.dict(os.environ, {"QUANTUM_TEST_MODE": "false"}):
            self.assertEqual(self.client.post("/execute", json=BODY, headers=self.headers).status_code, 403)
        self.assertEqual(self.client.post("/execute", json={**BODY, "mode": "live"}, headers=self.headers).status_code, 403)

    def test_invalid_references_and_limits(self):
        import copy
        invalid = copy.deepcopy(BODY)
        invalid["snapshot"]["tasks"][0]["agentId"] = "missing"
        self.assertEqual(self.client.post("/execute", json=invalid, headers=self.headers).status_code, 422)
        invalid = copy.deepcopy(BODY)
        invalid["limits"]["maxCalls"] = 0
        self.assertEqual(self.client.post("/execute", json=invalid, headers=self.headers).status_code, 422)

    def test_proposal_and_cancel(self):
        response = self.client.post("/propose", json={"prompt": "Check evidence", "mode": "test"}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["simulated"])
        self.assertEqual(self.client.post("/cancel", json={"runId": "cancel-test", "attemptId": "a"}, headers=self.headers).status_code, 200)

    def test_simulation_runs_actual_crewai(self):
        from worker import execute
        from crewai import Crew
        original = Crew.kickoff
        calls = []
        def kickoff(crew, *args, **kwargs):
            calls.append(True)
            return original(crew, *args, **kwargs)
        with patch.object(Crew, "kickoff", kickoff), patch.object(BudgetGateway, "call", side_effect=AssertionError("No gateway in simulation")):
            result = execute(BODY)
        self.assertEqual(len(calls), 1)
        self.assertTrue(result["simulated"])
        self.assertIn("SIMULATED", result["outputs"][0]["output"])
        self.assertEqual(result["usage"]["calls"], 0)

    def test_health_identifies_lock(self):
        import hashlib
        from pathlib import Path
        expected = hashlib.sha256(Path(__file__).with_name("uv.lock").read_bytes()).hexdigest()
        self.assertEqual(self.client.get("/health").json()["lockSha256"], expected)

    def test_timeout_kills_process_group(self):
        import subprocess
        from unittest.mock import MagicMock
        process = MagicMock(pid=123456)
        process.communicate.side_effect = subprocess.TimeoutExpired("worker", 1)
        with patch("app.subprocess.Popen", return_value=process), patch("app.os.killpg") as kill:
            response = self.client.post("/execute", json=BODY, headers=self.headers)
        self.assertEqual(response.status_code, 504)
        kill.assert_called_once()
        process.wait.assert_called_once()

    def test_missing_usage_closes_gateway(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        client = MagicMock()
        client.chat.completions.create.return_value = SimpleNamespace(usage=None)
        gateway = BudgetGateway({"maxCalls": 2, "maxTokens": 10000, "timeoutSeconds": 1})
        with patch.dict(os.environ, {"SMART_ROUTER_BASE_URL": "http://example.invalid", "SMART_ROUTER_API_KEY": "test"}), patch("openai.OpenAI", return_value=client):
            with self.assertRaises(RuntimeError):
                gateway.call("smart-worker", [{"role": "user", "content": "x"}])
            with self.assertRaises(RuntimeError):
                gateway.call("smart-worker", [{"role": "user", "content": "x"}])
        self.assertTrue(gateway.poisoned)
        self.assertEqual(client.chat.completions.create.call_count, 1)

    def test_actual_crewai_with_mock_gateway(self):
        from worker import execute
        with patch.object(BudgetGateway, "call", return_value="Final Answer: A checked summary."):
            result = execute({**BODY, "mode": "live"})
        self.assertFalse(result["simulated"])
        self.assertEqual(result["outputs"][0]["output"], "A checked summary.")

    def test_budget_denies_before_network(self):
        gateway = BudgetGateway({"maxCalls": 1, "maxTokens": 1, "timeoutSeconds": 1})
        with self.assertRaises(RuntimeError):
            gateway.call("smart-worker", [{"role": "user", "content": "x"}])
        self.assertTrue(gateway.poisoned)
        self.assertEqual(gateway.calls, 0)

if __name__ == "__main__":
    unittest.main()
