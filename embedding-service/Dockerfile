FROM python:3.12-slim

WORKDIR /app
RUN pip install --no-cache-dir onnxruntime tokenizers numpy
COPY slim-model/tokenizer.json /app/model/tokenizer.json
COPY slim-model/model_quantized.onnx /app/model/model_quantized.onnx
COPY app_onnx.py app.py
EXPOSE 80
CMD ["python", "app.py"]
