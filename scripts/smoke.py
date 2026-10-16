"""Check real local service communication after npm run dev."""
import json
import urllib.request

def get(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        assert response.status == 200
        return response.read()

runtime = json.loads(get("http://127.0.0.1:8200/health"))
assert runtime["adapter"] == "crewai"
api = json.loads(get("http://127.0.0.1:8100/api/foundation"))
assert api == {"status": "ready", "database": "ready", "runtime": "ready"}
page = get("http://127.0.0.1:3100").decode()
assert "Quantum" in page and "Local services are ready" in page
assert "What would you like help with?" in page and "Your team workspace" not in page
assert "Sign in" in page
workspace = get("http://127.0.0.1:3100/workspace").decode()
assert "Quantum" in workspace
print("PASS: web -> API -> PostgreSQL/runtime; live model calls remain gated")
