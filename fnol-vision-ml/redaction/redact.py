"""Blurs faces and licence plates in a claim photo before it is sent to an external AI model.

Faces: YuNet (face_detection_yunet_2023mar.onnx, MIT licence, OpenCV model zoo), run by OpenCV's FaceDetectorYN.
Plates: OpenCV's Haar cascade for number plates (haarcascade_russian_plate_number.xml, ships with OpenCV). It was
trained on one plate style and misses many plates; it is a best-effort layer, not a guarantee (see README).

Thresholds differ by line of business, measured on the project's photos (evaluate_redaction.py): vehicle damage photos
rarely show people, and a false "face" on a dented panel would hide the damage, so AUTO needs a stronger face score;
home photos often show residents and have no plates.

The original photo is never changed: only the copy sent outside is blurred, by pixelating each (enlarged) box and
blurring it, so nothing readable is left to sharpen back.
"""
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
FACE_MODEL = os.path.join(HERE, "face_detection_yunet_2023mar.onnx")
PLATE_CASCADE = os.path.join(cv2.data.haarcascades, "haarcascade_russian_plate_number.xml")
MAX_SIDE = 1280                                      # photos are processed (and returned) at most this large
FACE_SCORE = {"AUTO": 0.85, "HOME": 0.75}            # real faces in FEMA photos mostly >= 0.8; car-panel false hits <= 0.83
PLATE_NEIGHBOURS = 12                                # stricter than OpenCV's default (fewer false plates)
GROW = 0.25                                          # each box is enlarged by a quarter on every side

_plates = cv2.CascadeClassifier(PLATE_CASCADE)


def load(image_bytes):
    """Decodes and downsizes a photo; raises ValueError when it is not an image."""
    img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("unreadable image")
    scale = MAX_SIDE / max(img.shape[:2])
    if scale < 1:
        img = cv2.resize(img, (int(img.shape[1] * scale), int(img.shape[0] * scale)), interpolation=cv2.INTER_AREA)
    return img


def faces(img, line):
    h, w = img.shape[:2]
    detector = cv2.FaceDetectorYN.create(FACE_MODEL, "", (w, h), FACE_SCORE.get(line, 0.85), 0.3, 5000)
    _, found = detector.detect(img)
    return [] if found is None else [tuple(int(v) for v in f[:4]) for f in found]


def plates(img, line):
    if line == "HOME":
        return []
    grey = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    min_w = max(24, img.shape[1] // 40)
    area = img.shape[0] * img.shape[1]
    boxes = _plates.detectMultiScale(grey, scaleFactor=1.08, minNeighbors=PLATE_NEIGHBOURS, minSize=(min_w, min_w // 4))
    # Plate-shaped boxes only: wider than tall (2:1 to 6:1) and small, so a damaged panel is not blurred.
    return [tuple(int(v) for v in (x, y, w, h)) for x, y, w, h in boxes
            if 2.0 <= w / max(h, 1) <= 6.0 and w * h <= 0.03 * area]


def grown(img, boxes):
    """Boxes (x, y, w, h) enlarged by GROW and clipped: (x0, y0, x1, y1)."""
    h, w = img.shape[:2]
    out = []
    for x, y, bw, bh in boxes:
        gx, gy = int(bw * GROW), int(bh * GROW)
        x0, y0, x1, y1 = max(0, x - gx), max(0, y - gy), min(w, x + bw + gx), min(h, y + bh + gy)
        if x1 > x0 and y1 > y0:
            out.append((x0, y0, x1, y1))
    return out


def blur(img, boxes):
    for x0, y0, x1, y1 in grown(img, boxes):
        rw, rh = x1 - x0, y1 - y0
        # Pixelate (to at most 6 cells across) and then blur.
        cells = cv2.resize(img[y0:y1, x0:x1], (max(1, min(6, rw)), max(1, min(6, rh))), interpolation=cv2.INTER_AREA)
        coarse = cv2.resize(cells, (rw, rh), interpolation=cv2.INTER_NEAREST)
        img[y0:y1, x0:x1] = cv2.GaussianBlur(coarse, (0, 0), max(2.0, min(rw, rh) / 8))
    return img


def redact(image_bytes, line="AUTO"):
    """-> (jpeg_bytes, faces, plates). Raises ValueError for an image OpenCV cannot decode."""
    img = load(image_bytes)
    found_faces, found_plates = faces(img, line), plates(img, line)
    img = blur(img, found_faces + found_plates)
    ok, jpeg = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 90])
    if not ok:
        raise ValueError("could not encode the redacted image")
    return jpeg.tobytes(), len(found_faces), len(found_plates)
