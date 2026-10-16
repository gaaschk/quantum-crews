FROM python:3.12-slim-bookworm
ARG SOURCE_REVISION=local
ENV QUANTUM_SOURCE_REVISION=$SOURCE_REVISION
RUN pip install --no-cache-dir uv==0.11.9
WORKDIR /app
ENV OTEL_SDK_DISABLED=true CREWAI_TELEMETRY_DISABLED=true UV_LINK_MODE=copy
COPY services/runtime/pyproject.toml services/runtime/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
COPY services/runtime/app.py services/runtime/worker.py ./
RUN useradd --create-home --uid 10001 runtime && chown -R runtime:runtime /app
COPY LICENSE NOTICE /app/
USER runtime
CMD [".venv/bin/uvicorn","app:app","--host","0.0.0.0","--port","8200"]
