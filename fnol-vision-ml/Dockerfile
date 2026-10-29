# Second-opinion vision model (CPU). The CLIP encoder is downloaded at build time as safetensors only.
FROM python:3.12-slim

ENV PIP_NO_CACHE_DIR=1 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HF_HUB_DISABLE_TELEMETRY=1
WORKDIR /app
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.5.1 \
 && pip install transformers==4.46.3 pillow==11.0.0 numpy==2.1.3 huggingface_hub==0.26.2

# Encoder weights: LAION CLIP ViT-B/32 (MIT licence). Only safetensors and config files, never pickle files.
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('laion/CLIP-ViT-B-32-laion2B-s34B-b79K', local_dir='/models/clip', allow_patterns=['model.safetensors', '*.json', '*.txt'])"

# Privacy redaction: OpenCV (Apache-2.0) with the YuNet face detector (MIT, redaction/*.onnx) and its plate cascade.
RUN pip install opencv-python-headless==4.10.0.84

COPY model/ /app/model/
COPY redaction/ /app/redaction/
COPY app.py /app/app.py

RUN useradd --create-home --uid 10001 vision && chown -R vision /app /models
USER vision
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/health', timeout=4)"
CMD ["python", "app.py"]
