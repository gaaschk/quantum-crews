"""One isolated subprocess per attempt; never retries provider requests."""
import json
import os
import sys

os.environ["OTEL_SDK_DISABLED"] = "true"
os.environ["CREWAI_TELEMETRY_DISABLED"] = "true"

class BudgetGateway:
    def __init__(self, limits):
        self.limits = limits
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
            from openai import OpenAI
            base_url = os.environ["SMART_ROUTER_BASE_URL"].rstrip("/")
            if not base_url.endswith("/v1"):
                base_url += "/v1"
            client = OpenAI(base_url=base_url, api_key=os.environ["SMART_ROUTER_API_KEY"], max_retries=0,
                            timeout=self.limits["timeoutSeconds"])
            response = client.chat.completions.create(model=model, messages=messages, max_tokens=min(remaining, 4096), stream=False)
            usage = response.usage
            if usage is None or not isinstance(usage.total_tokens, int) or usage.total_tokens < 1:
                raise RuntimeError("Gateway omitted trustworthy token usage")
            self.tokens += usage.total_tokens
            if self.tokens > self.limits["maxTokens"]:
                raise RuntimeError("Gateway exceeded token limit")
            text = response.choices[0].message.content
            if not text or response.choices[0].message.tool_calls:
                raise RuntimeError("Gateway returned unsupported output")
            return text
        except Exception:
            self.poisoned = True
            raise

    def usage(self):
        return {"calls": self.calls, "totalTokens": self.tokens, "costUsd": None}


def execute(payload):
    from crewai import Agent, BaseLLM, Crew, Process, Task
    from pydantic import PrivateAttr
    gateway = BudgetGateway(payload["limits"])
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
            return self._gateway.call("smart-worker", messages)
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
    gateway = BudgetGateway({"maxCalls": 1, "maxTokens": 16000, "timeoutSeconds": 50})
    text = gateway.call("smart-orchestrator", [{"role": "system", "content": 'Return only a JSON object containing name, role, instructions (nonempty strings). Propose a team member. Never include permissions, tools, IDs, or other fields.'},
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
