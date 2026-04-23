FROM python:3.12-slim

WORKDIR /app

# Runtime-only deps: inference + HF file downloads (no torch, no optimum).
RUN pip install --no-cache-dir \
        onnxruntime \
        tokenizers \
        numpy \
        huggingface_hub

COPY app_onnx.py app.py

# Persistent cache for downloaded (or manually placed) models.
VOLUME ["/app/models"]

EXPOSE 80
CMD ["python", "app.py"]
