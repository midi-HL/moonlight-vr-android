# Synthetic VR video orientation fixture

`vr-orientation.mp4` is a self-generated calibration image encoded as a one-second,
5 fps, 256x144 H.264 Constrained Baseline video. Every frame is an I-frame, with
8-bit YUV420 SDR BT.709 colour and no audio or B-frames. It contains no external media.

Source orientation:

| | Left | Right |
|---|---|---|
| Top | Red, `1 TL` | Green, `2 TR` |
| Bottom | Blue, `3 BL` | Yellow, `4 BR` |

The small `UP` and `R->` labels and central circle also support visual inspection.
The runtime test compares colour-region centroids in each eye; it must not change
the expected source order merely to accommodate an inverted default rendering.

SHA-256: `78c1e1a1a4586c6e76c52b9aacd7335b380fd53240a5f774f98b56dcfbf6751d`

Regenerator in the audit workspace:
`artifacts/vr-audit-2026-11-19/make-video-fixture.py` (Pillow plus an existing ffmpeg).
The fixture tests local MediaCodec/SurfaceTexture/OES/zoom rendering; it does not
stand in for a real Sunshine network session, decoder hot-switch recovery, or headset optics.
