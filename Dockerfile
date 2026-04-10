FROM python:3.11-slim-bookworm

WORKDIR /app

# System deps
RUN apt-get update && apt-get install -y libpq-dev && rm -rf /var/lib/apt/lists/*

# Install dependencies first (cache layer)
COPY pyproject.toml .
COPY hivemem/__init__.py hivemem/
# Install CPU-only torch first to avoid pulling 2GB+ CUDA deps
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir ".[dev]" 2>/dev/null || pip install --no-cache-dir .

# Copy actual code
COPY hivemem/ hivemem/
COPY scripts/ scripts/

EXPOSE 8420

CMD ["python", "-m", "hivemem.server"]
