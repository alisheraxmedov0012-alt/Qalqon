# MobileFaceNet TFLite model

Bundled model: `app/src/main/assets/models/mobile_face_net.tflite`
(`TfLiteMobileFaceNet` loads this exact path).

## Provenance

- Source: [`syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing`](https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing)
  (`app/src/main/assets/MobileFaceNet.tflite`).
- Network: MobileFaceNet (via [`sirius-ai/MobileFaceNet_TF`](https://github.com/sirius-ai/MobileFaceNet_TF)).
- License: MIT (Copyright (c) 2019 syaringan357). See the upstream repository
  for the full license text.
- Size: 5,233,396 bytes
- SHA-256: `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`

## Model contract (verified from the model's tensor metadata)

| Property | Value |
|---|---|
| Input | float32, `[2, 112, 112, 3]` (NHWC, RGB) — fixed batch of 2 |
| Input normalization | `(pixel - 127.5) / 128.0` |
| Output | float32, `[2, 192]` — 192-D embedding |

`TfLiteMobileFaceNet` reads the real input/output shapes from the model at
load time. Because the batch dimension is fixed at 2 and the two batch slots
are independent, the same face is written into both slots and slot 0 is read
back; the embedding is L2-normalized before use. Normalization defaults live
in `FaceEmbeddingConfig` (`core/embed/FaceEmbeddingModel.kt`).

## Replacing the model

Any MobileFaceNet build with an NHWC input of shape `[B, H, W, C]` works: the
batch/height/width are read from the input tensor and the embedding size from
the output tensor. Only the normalization may need updating in
`FaceEmbeddingConfig`.

## Fallback

If the file is missing or fails to load, `TfLiteMobileFaceNet.isReady()`
returns `false` and the app falls back to the geometry vector extractor
(`FaceFeatureExtractor`). Enrollment and recognition keep working, but with
geometry-only matching instead of the AI embedding.
