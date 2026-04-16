from optimum.onnxruntime import ORTModelForFeatureExtraction
from transformers import AutoTokenizer

repo = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
model = ORTModelForFeatureExtraction.from_pretrained(repo, export=True)
model.save_pretrained("/app/model")
tokenizer = AutoTokenizer.from_pretrained(repo)
tokenizer.save_pretrained("/app/model")
print("Model saved to /app/model")
