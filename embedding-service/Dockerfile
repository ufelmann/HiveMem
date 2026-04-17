FROM python:3.12-slim AS download

WORKDIR /app
RUN pip install --no-cache-dir optimum[onnxruntime] transformers
COPY download_model.py .
RUN python download_model.py

FROM python:3.12-slim

WORKDIR /app
RUN pip install --no-cache-dir onnxruntime tokenizers numpy
COPY --from=download /app/model/ /app/model/
COPY app_onnx.py app.py
EXPOSE 80
CMD ["python", "app.py"]
