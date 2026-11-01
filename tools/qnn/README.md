# Qualcomm QNN W8A8 model

Komikku commits the QNN DLCs directly under `app/src/main/assets/qnn-models/`
(unlike upstream, which stages them from a local directory at build time). The
bundled set is 15 FP16 DLCs plus one prequantized **W8A8** DLC:

| Model | FP16 | INT8 W8A8 |
| --- | --- | --- |
| Real-ESRGAN Anime (animevideov3 2x) | Yes | Yes |
| Real-ESRGAN Photo (general-x4v3, 2x output) | Yes | No |
| Real-CUGAN SE 2x, all five variants | Yes | No |
| Real-CUGAN Pro 2x/3x, all six variants | Yes | No |
| SPAN NomosUni Photo 2x | Yes | No |
| W2xEX Photo Small 2x | Yes | No |

Only Anime exposes the INT8 option in the reader settings; switching the
Real-ESRGAN style to Photo removes it. The other fourteen variants stay
FP16-only because 8-bit activations carry a precision risk for their graphs.
`QnnModelContexts.supportsW8a8` and `build.gradle.kts`'s upstream counterpart
must stay in sync on this allowlist.

## Provenance of the committed W8A8 DLC

`realesrgan-animevideov3-x2-w8a8.dlc` is extracted from the upstream
[v1.3.17-beta.2 release APK](https://github.com/HaoweiLi97/mihon_img_upscale/releases/tag/v1.3.17-beta.2)
(`assets/qnn-models/`), and its SHA-256 is recorded in
`app/src/main/assets/qnn-models/manifest.json`. Upstream validated that artifact
with QNN CPU simulation against the FP32 ONNX on eight held-out manga crops
(PSNR 34.21-38.00 dB) and host compilation for HTP v69/v73/v75/v79/v81;
physical-device inference is covered by `QualcommHtpDeviceTest` upstream.

## Regenerating

Requires QAIRT 2.49.0 and developer-side manga calibration crops (normalized
float32 RGB/NHWC 256x256 inputs listed in a text file):

```sh
export QNN_SDK_ROOT=/path/to/qairt/2.49.0.260730
FP16_DLC_DIR=/path/to/dlc-fp16 OUTPUT_DIR=/path/to/dlc-w8a8 \
MANGA_CALIBRATION_INPUT_LIST=/private/path/to/input-list.txt \
  sh tools/qnn/convert_w8a8_dlc_models.sh
```

The script quantizes with 8-bit weights and activations, 32-bit biases,
per-channel convolution weights and cross-layer equalization, then runs
`verify_w8a8_dlc.py`, which rejects any DLC containing floating-point or wider
tensors. Copy the result into `app/src/main/assets/qnn-models/` and update its
manifest entry. The conversion rejects model names outside the Anime allowlist.

Older `convert_int8_models.sh` outputs may use W8A16 and must not be renamed
W8A8.
