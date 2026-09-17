# MobileFaceNet TFLite model

Place a MobileFaceNet `.tflite` model here as:

```
app/src/main/assets/models/mobile_face_net.tflite
```

`TfLiteMobileFaceNet` loads this exact path. The file is intentionally **not
committed** (binary weights are large and model-specific).

## Expected model contract

| Property | Value |
|---|---|
| Input | float32, `[1, 112, 112, 3]` (NHWC, RGB) |
| Input normalization | `(pixel / 255.0 - 0.5) * 2.0` → `[-1, 1]` |
| Output | float32, `[1, N]` where `N` is the embedding size (commonly 128, 192, or 512) |

The input width/height/channels and normalization are configurable in
`FaceEmbeddingConfig` (`core/embed/FaceEmbeddingModel.kt`); the embedding size
is read from the output tensor at load time, so any MobileFaceNet build that
matches the NHWC input contract works.

## Without a model

If the file is missing or fails to load, `TfLiteMobileFaceNet.isReady()`
returns `false` and the app transparently falls back to the geometry vector
extractor (`FaceFeatureExtractor`). Enrollment and recognition keep working,
but with the previous geometry-only matching rather than the AI embedding.
