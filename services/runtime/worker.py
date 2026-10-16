"""One isolated subprocess per attempt; never retries provider requests."""
import json
import os
import sys

os.environ["OTEL_SDK_DISABLED"] = "true"
os.environ["CREWAI_TELEMETRY_DISABLED"] = "true"

class BudgetGateway:
    def __init__(self, limits, connection=None):
        self.limits = limits
        self.connection = connection
        self.calls = self.tokens = 0
        self.poisoned = False

    def call(self, model, messages):
        if self.poisoned:
            raise RuntimeError("Gateway budget is closed")
        # Reserve the full UTF-8 request size as a conservative input-token ceiling.
        input_bound = len(json.dumps(messages, ensure_ascii=False).encode("utf-8")) + 1024
        remaining = self.limits["maxTokens"] - self.tokens - input_bound
        if self.calls >= self.limits["maxCalls"] or remaining <= 0:
            self.poisoned = True
            raise RuntimeError("Model call or token limit exceeded")
        self.calls += 1
        try:
            connection = self.connection
            if not connection or connection.get("provider") not in {"smart-router", "openai", "anthropic"} or not connection.get("apiKey"):
                raise RuntimeError("A user-owned model connection is required")
            if connection["provider"] == "anthropic":
                import httpx
                system = "\n".join(str(item["content"]) for item in messages if item["role"] == "system")
                chat = [{"role": item["role"], "content": item["content"]} for item in messages if item["role"] != "system"]
                with httpx.Client(timeout=self.limits["timeoutSeconds"], follow_redirects=False) as client:
                    response = client.post("https://api.anthropic.com/v1/messages", headers={"x-api-key": connection["apiKey"], "anthropic-version": "2023-06-01"},
                        json={"model": model, "system": system, "messages": chat, "max_tokens": min(remaining, 4096)})
                    response.raise_for_status()
                    data = response.json()
                usage = data["usage"]
                used = usage["input_tokens"] + usage["output_tokens"]
                if any(item.get("type") != "text" for item in data["content"]):
                    raise RuntimeError("Unsupported provider output")
                text = "".join(item["text"] for item in data["content"])
            else:
                from openai import OpenAI
                base_url = "https://api.openai.com/v1"
                if connection["provider"] == "smart-router":
                    base_url = os.environ.get("SMART_ROUTER_BASE_URL", "https://smart-router.kevingaasch.com").rstrip("/")
                    if not base_url.startswith("https://"):
                        raise RuntimeError("Router endpoint must use HTTPS")
                    if not base_url.endswith("/v1"):
                        base_url += "/v1"
                client = OpenAI(base_url=base_url, api_key=connection["apiKey"], max_retries=0, timeout=self.limits["timeoutSeconds"])
                token_limit = {"max_tokens" if connection["provider"] == "smart-router" else "max_completion_tokens": min(remaining, 4096)}
                response = client.chat.completions.create(model=model, messages=messages, **token_limit, stream=False)
                used = response.usage.total_tokens if response.usage else None
                if not isinstance(used, int) or isinstance(used, bool) or used < 1:
                    raise RuntimeError("Provider omitted trustworthy token usage")
                text = response.choices[0].message.content
                if response.choices[0].message.tool_calls:
                    raise RuntimeError("Unsupported provider output")
            if not isinstance(used, int) or isinstance(used, bool) or used < 1:
                raise RuntimeError("Provider omitted trustworthy token usage")
            self.tokens += used
            if self.tokens > self.limits["maxTokens"]:
                raise RuntimeError("Provider exceeded token limit")
            if not text:
                raise RuntimeError("Provider returned empty output")
            return text
        except Exception:
            self.poisoned = True
            raise

    def usage(self):
        return {"calls": self.calls, "totalTokens": self.tokens, "costUsd": None}


def execute(payload):
    if payload["mode"] == "live" and not payload.get("connection"):
        raise RuntimeError("A user-owned model connection is required")
    from crewai import Agent, BaseLLM, Crew, Process, Task
    from pydantic import PrivateAttr
    gateway = BudgetGateway(payload["limits"], payload.get("connection"))
    simulated = payload["mode"] == "test"
    if simulated:
        if os.getenv("QUANTUM_TEST_MODE") != "true":
            raise RuntimeError("Simulation is disabled")

    class RouterLLM(BaseLLM):
        _gateway: object = PrivateAttr()
        def call(self, messages, tools=None, callbacks=None, available_functions=None, **kwargs):
            if tools or available_functions:
                raise RuntimeError("Tools are disabled")
            if isinstance(messages, str):
                messages = [{"role": "user", "content": messages}]
            if simulated:
                return "Final Answer: SIMULATED: " + payload["snapshot"]["input"][:1000]
            return self._gateway.call(payload["connection"]["workerModel"], messages)
    llm = RouterLLM(model="smart-worker")
    llm._gateway = gateway
    agents = {item["id"]: Agent(role=item["role"], goal=item["instructions"], backstory=item["name"], llm=llm,
                tools=[], allow_delegation=False, max_iter=2, max_retry_limit=0, verbose=False) for item in payload["snapshot"]["agents"]}
    tasks = [Task(description=item["instructions"] + "\nSource input:\n" + payload["snapshot"]["input"],
                  expected_output="A clear answer to the task", agent=agents[item["agentId"]]) for item in payload["snapshot"]["tasks"]]
    crew = Crew(agents=list(agents.values()), tasks=tasks, process=Process.sequential, memory=False, cache=False,
                planning=False, verbose=False, share_crew=False)
    crew.kickoff()
    if gateway.poisoned:
        raise RuntimeError("Execution failed closed after gateway error")
    return {"outputs": [{"taskId": item["id"], "output": task.output.raw} for item, task in zip(payload["snapshot"]["tasks"], tasks)],
            "usage": gateway.usage(), "simulated": simulated}


def propose(payload):
    if payload["mode"] == "test":
        if os.getenv("QUANTUM_TEST_MODE") != "true":
            raise RuntimeError("Simulation is disabled")
        return {"name": "Reviewer", "role": "Quality reviewer", "instructions": payload["prompt"], "simulated": True}
    gateway = BudgetGateway({"maxCalls": 1, "maxTokens": 16000, "timeoutSeconds": 50}, payload.get("connection"))
    text = gateway.call(payload["connection"]["plannerModel"], [{"role": "system", "content": 'Return only a JSON object containing name, role, instructions (nonempty strings). Propose a team member. Never include permissions, tools, IDs, or other fields.'},
        {"role": "user", "content": json.dumps({"request": payload["prompt"], "existing": payload.get("base")})}])
    result = json.loads(text)
    if set(result) != {"name", "role", "instructions"} or any(not isinstance(value, str) or not value.strip() or len(value) > 20000 for value in result.values()):
        raise RuntimeError("Invalid proposal from model")
    return {**result, "simulated": False}

if __name__ == "__main__":
    payload = json.loads(sys.stdin.read())
    with open(os.devnull, "w") as sink:
        sys.stdout = sink
        try:
            result = execute(payload) if sys.argv[1] == "execute" else propose(payload)
        except Exception:
            result = {"error": "Runtime execution failed; check configuration and policy limits"}
    sys.__stdout__.write(json.dumps(result))
