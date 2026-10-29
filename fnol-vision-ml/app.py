"""fnol-vision-ml: the second-opinion vision model behind the MODEL_DISAGREEMENT gate check.

A CLIP image encoder plus the heads trained by tools/second-opinion/build_model.py:
  - auto:    damaged vehicle area (logistic regression on CLIP embeddings, trained on CC0 car-damage photos)
  - home:    cause of loss (zero-shot from text descriptions)
  - subject: what the photos show (zero-shot)

POST /v1/redact
  {"line": "AUTO" | "HOME", "images": ["data:image/jpeg;base64,..."]}
  -> {"images": ["data:image/jpeg;base64,..."], "faces": [n, ...], "plates": [n, ...]}
  Faces and licence plates blurred (redaction/redact.py), for photos about to leave for an external AI model.
POST /v1/second-opinion
  {"line": "AUTO" | "HOME", "images": ["data:image/jpeg;base64,..."], "angles": ["FRONT", "CLOSE_UP", ...]}
  -> {"model": ..., "line": ..., "photos": n,
      "damageProbabilities": {class: p}, "subjectProbabilities": {class: p}}
GET  /health

Internal service: it is reachable only on the compose network (no published port), accepts only base64 JPEG, PNG,
WebP or GIF data URIs (no URLs, so it never fetches anything), and caps request size, photo count and pixels.
"""
import base64
import binascii
import io
import json
import logging
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import torch
from PIL import Image
from transformers import CLIPModel, CLIPProcessor

from redaction.redact import redact as redact_photo

log = logging.getLogger("fnol-vision-ml")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

MODEL_FILE = os.environ.get("MODEL_FILE", os.path.join(os.path.dirname(__file__), "model", "second-opinion-model.json"))
ENCODER_DIR = os.environ.get("ENCODER_DIR", "/models/clip")
PORT = int(os.environ.get("PORT", "8090"))
MAX_BODY = 40 * 1024 * 1024
MAX_IMAGES = 8
DATA_URI = re.compile(r"^data:image/(jpeg|png|webp|gif);base64,", re.I)
Image.MAX_IMAGE_PIXELS = 40_000_000           # refuse decompression bombs
torch.set_num_threads(int(os.environ.get("TORCH_THREADS", "2")))

with open(MODEL_FILE, encoding="utf-8") as f:
    HEADS = json.load(f)
CLIP = CLIPModel.from_pretrained(ENCODER_DIR, use_safetensors=True).eval()
PROCESSOR = CLIPProcessor.from_pretrained(ENCODER_DIR)
AUTO_W = np.array(HEADS["auto"]["coef"])
AUTO_B = np.array(HEADS["auto"]["intercept"])
SUBJECT_T = np.array(HEADS["subject"]["textEmbeddings"])
HOME_T = np.array(HEADS["home"]["textEmbeddings"])
SCALE = HEADS["logitScale"]
MODEL_ID = f"second-opinion v{HEADS['version']} ({HEADS['encoder'].split('/')[-1]})"


def softmax(x):
    e = np.exp(x - x.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


def decode(uri):
    if not isinstance(uri, str) or not DATA_URI.match(uri):
        raise ValueError("only base64 image data URIs are accepted")
    try:
        raw = base64.b64decode(uri.split(",", 1)[1], validate=True)
    except (binascii.Error, ValueError) as e:
        raise ValueError("invalid base64 image") from e
    image = Image.open(io.BytesIO(raw))
    image.draft("RGB", (448, 448))             # JPEG: decode at reduced size, CLIP only needs 224 px
    return image.convert("RGB")


def embed(images):
    with torch.no_grad():
        feats = CLIP.get_image_features(**PROCESSOR(images=images, return_tensors="pt"))
    return torch.nn.functional.normalize(feats, dim=-1).numpy()


def raw_bytes(uri):
    if not isinstance(uri, str) or not DATA_URI.match(uri):
        raise ValueError("only base64 image data URIs are accepted")
    try:
        return base64.b64decode(uri.split(",", 1)[1], validate=True)
    except (binascii.Error, ValueError) as e:
        raise ValueError("invalid base64 image") from e


def redact_all(line, uris):
    images, faces, plates = [], [], []
    for uri in uris:
        jpeg, f, p = redact_photo(raw_bytes(uri), line)
        images.append("data:image/jpeg;base64," + base64.b64encode(jpeg).decode())
        faces.append(f)
        plates.append(p)
    return {"images": images, "faces": faces, "plates": plates}


def assess(line, uris, angles):
    images = [decode(u) for u in uris]
    emb = embed(images)
    subject = softmax(SCALE * emb @ SUBJECT_T.T).mean(0)
    if line == "AUTO":
        per_photo = softmax(emb @ AUTO_W.T + AUTO_B)
        # The close-up shows the damage; the other views show mostly undamaged sides, so use it when there is one.
        close = [i for i, a in enumerate(angles) if a == "CLOSE_UP"]
        damage = per_photo[close].mean(0) if close else per_photo.mean(0)
        classes = HEADS["auto"]["classes"]
    else:
        damage = softmax(SCALE * emb @ HOME_T.T).mean(0)
        classes = HEADS["home"]["classes"]
    rnd = lambda v: round(float(v), 4)
    return {"model": MODEL_ID, "line": line, "photos": len(images),
            "damageProbabilities": {c: rnd(p) for c, p in zip(classes, damage)},
            "subjectProbabilities": {c: rnd(p) for c, p in zip(HEADS["subject"]["classes"], subject)}}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "UP", "model": MODEL_ID})
        else:
            self._send(404, {"error": "NOT_FOUND"})

    def do_POST(self):
        if self.path not in ("/v1/second-opinion", "/v1/redact"):
            return self._send(404, {"error": "NOT_FOUND"})
        if not self.headers.get("Content-Length"):
            return self._send(411, {"error": "LENGTH_REQUIRED", "message": "send Content-Length (no chunked bodies)"})
        length = int(self.headers.get("Content-Length"))
        if length <= 0 or length > MAX_BODY:
            return self._send(413, {"error": "REQUEST_TOO_LARGE"})
        try:
            req = json.loads(self.rfile.read(length))
            line = str(req.get("line", "AUTO")).upper()
            uris = req.get("images") or []
            angles = [str(a).upper() for a in (req.get("angles") or [])] + ["OTHER"] * len(uris)
            if line not in ("AUTO", "HOME") or not uris or len(uris) > MAX_IMAGES:
                return self._send(400, {"error": "BAD_REQUEST", "message": f"line AUTO|HOME and 1-{MAX_IMAGES} images"})
            started = time.time()
            if self.path == "/v1/redact":
                result = redact_all(line, uris)
                log.info("redact line=%s photos=%d faces=%d plates=%d in %.0f ms", line, len(uris),
                         sum(result["faces"]), sum(result["plates"]), (time.time() - started) * 1000)
            else:
                result = assess(line, uris, angles[:len(uris)])
                log.info("second opinion line=%s photos=%d in %.0f ms", line, len(uris), (time.time() - started) * 1000)
            self._send(200, result)
        except (ValueError, OSError, Image.DecompressionBombError) as e:
            self._send(400, {"error": "BAD_IMAGE", "message": str(e)[:200]})
        except Exception:   # never leak internals to the caller
            log.exception("%s failed", self.path)
            self._send(500, {"error": "INTERNAL_ERROR"})

    def log_message(self, fmt, *args):   # request logging is done above, without bodies
        pass


if __name__ == "__main__":
    log.info("serving %s on :%d", MODEL_ID, PORT)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
