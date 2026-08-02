FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# app.py is the threaded HTTP entrypoint; server.py holds the HTTP handler; backend*.py
# hold the interchangeable runtimes.
# backend_ollama.py is deliberately not listed here -- it does not exist until Task 9,
# and COPY of a missing file fails the build. Task 9 adds it to this line.
COPY app.py server.py backend.py backend_onnx.py ./

# Persistent cache for downloaded (or manually placed) models.
VOLUME ["/app/models"]

EXPOSE 80
CMD ["python", "app.py"]
