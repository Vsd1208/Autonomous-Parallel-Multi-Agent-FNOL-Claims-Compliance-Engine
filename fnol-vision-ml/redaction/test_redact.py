"""Photo redaction, with no model downloads. Run: python -m pytest fnol-vision-ml/redaction/test_redact.py

These check behaviour: what happens to bad input, and that a detected box really is blurred. How often the detectors find a face or plate
is a separate, measured question: see evaluate_robustness.py and docs/AI_MODEL_EVALUATION.md.
"""
import os
import sys

import cv2
import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import redact as r  # noqa: E402


def photo(w=640, h=480):
    rng = np.random.default_rng(7)
    img = rng.integers(0, 255, (h, w, 3), dtype=np.uint8)
    ok, data = cv2.imencode(".jpg", img)
    assert ok
    return data.tobytes()


@pytest.mark.parametrize("bad", [b"", b"not an image", b"<svg xmlns='http://www.w3.org/2000/svg'></svg>", b"%PDF-1.7", photo()[:40]])
def test_corrupted_or_unsupported_input_raises(bad):
    """The service then answers with an error, and the Java side withholds the photo from the external model."""
    with pytest.raises(ValueError):
        r.redact(bad)


def test_detector_failure_is_not_swallowed(monkeypatch):
    """A detector that fails must never return the photo as if it were clean."""
    def broken(*_):
        raise RuntimeError("model missing")
    monkeypatch.setattr(r, "faces", broken)
    with pytest.raises(RuntimeError):
        r.redact(photo())


def test_multiple_faces_and_a_small_plate_are_all_blurred(monkeypatch):
    boxes = [(50, 50, 80, 80), (300, 60, 60, 60), (480, 200, 90, 90)]
    plate = [(200, 400, 48, 12)]
    monkeypatch.setattr(r, "faces", lambda img, line: boxes)
    monkeypatch.setattr(r, "plates", lambda img, line: plate)
    original = r.load(photo())
    out = r.redact(photo())
    data = out[0] if isinstance(out, tuple) else out
    blurred = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    assert blurred.shape == original.shape
    for x, y, w, h in boxes + plate:
        before = original[y:y + h, x:x + w].astype(float)
        after = blurred[y:y + h, x:x + w].astype(float)
        assert after.std() < before.std() * 0.5, f"box {x},{y} was not blurred"
    # Far from any box, the photo is still the photo.
    assert np.abs(blurred[300:340, 40:80].astype(float).std() - original[300:340, 40:80].astype(float).std()) < 30


@pytest.mark.parametrize("transform", ["rotated", "dark"])
def test_rotated_and_low_light_photos_are_processed(transform):
    img = cv2.imdecode(np.frombuffer(photo(), np.uint8), cv2.IMREAD_COLOR)
    img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE) if transform == "rotated" else (img * 0.1).astype(np.uint8)
    ok, data = cv2.imencode(".jpg", img)
    assert ok
    r.redact(data.tobytes())


@pytest.mark.parametrize("rotation,expected", [
    (cv2.ROTATE_90_CLOCKWISE, (100, 400, 30, 40)),
    (cv2.ROTATE_180, (500, 370, 40, 30)),
    (cv2.ROTATE_90_COUNTERCLOCKWISE, (510, 100, 30, 40)),
])
def test_a_face_found_sideways_is_mapped_back_onto_the_original(monkeypatch, rotation, expected):
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    x, y, w, h = expected
    img[y:y + h, x:x + w] = 255

    def upright(view, line):
        # The stand-in detector finds the bright box only in the one rotated view, wherever it landed there.
        if view.shape != cv2.rotate(img, rotation).shape or not np.array_equal(view, cv2.rotate(img, rotation)):
            return []
        ys, xs = np.where(view[:, :, 0] == 255)
        return [(int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1))]

    monkeypatch.setattr(r, "upright_faces", upright)
    assert r.faces(img, "AUTO") == [expected]


def test_large_photo_is_downsized():
    ok, data = cv2.imencode(".jpg", np.zeros((3000, 2000, 3), dtype=np.uint8))
    assert ok
    assert max(r.load(data.tobytes()).shape[:2]) <= r.MAX_SIDE
