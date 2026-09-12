# Third-party upscaling assets: provenance and licenses

This document records where every third-party binary bundled for the image
enhancement feature comes from, under which license it is distributed, and how
to verify the files have not been altered.

Scope: everything under `app/src/main/assets/{qnn-contexts,waifu2x-models*,
realcugan-models,realcugan-pro-models,realesrgan-models,w2xex-esrgan,
animejanai-ncnn-vulkan,sudo-ultracompact,span-nomosuni,anime4k}` and
`app/src/main/jniLibs/arm64-v8a/libQnnModelDlc.so`. The ncnn/vulkan inference
code in `app/src/main/cpp/` is built from source in this repository.

## Provenance chain

The enhancement port originates from
[HaoweiLi97/mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale)
("MIU"). MIU's v1.3.9 release was used as the source for:

- `jniLibs/arm64-v8a/libQnnModelDlc.so` — part of the **Qualcomm AI Runtime
  SDK (QNN)**, extracted by MIU from Qualcomm's SDK release.
- `assets/qnn-contexts/*.bin` — pre-serialized QNN HTP context binaries,
  compiled per model and per HTP architecture (v69/v73/v75/v79/v81).

Kotlin/JNI code was ported from MIU as well; local modifications are marked
with `// KMK -->` islands.

Model files bundled directly by this repository (previously fetched at runtime
by a download fallback that has been removed) come from the canonical upstream
sources:

| Asset | Upstream source |
|-------|-----------------|
| `w2xex-esrgan/{Universal-Fast-W2xEX,Omni-MiniV2-W2xEX,Photo-Small-W2xEX}` | https://huggingface.co/randomblock1/W2xEX-ESRGAN |
| `animejanai-ncnn-vulkan/animejanai-v2-ultra-compact-x2` | https://github.com/Justin62628/animejanai-ncnn-vulkan (models/) |
| `waifu2x-models`, `waifu2x-models-upconv7` (cunet / upconv_7) | https://github.com/nihui/waifu2x-ncnn-vulkan (models-cunet, models-upconv_7_anime_style_art_rgb) |
| `waifu2x-models-nose`, `realcugan-models` (SE), `realcugan-pro-models` | https://github.com/nihui/realcugan-ncnn-vulkan (models-nose, models-se, models-pro) |
| `realesrgan-models/v3-anime` (realesr-animevideov3) | https://github.com/xinntao/Real-ESRGAN releases (realesrgan-ncnn-vulkan) |
| `realesrgan-models/v3-general` (realesr-general-x4v3, x4) | https://github.com/xinntao/Real-ESRGAN |
| `sudo-ultracompact/2x-sudo-UltraCompact` | see `ATTRIBUTION.txt` in the directory |
| `span-nomosuni/2x-NomosUni-SPAN-multijpg-ldl` | see `ATTRIBUTION.txt` in the directory |
| `anime4k/*.glsl` | https://github.com/bloc97/Anime4K |

## Licenses

| Component | License (per upstream) | Notes |
|-----------|------------------------|-------|
| waifu2x / Real-CUGAN ncnn models | MIT | via nihui's ncnn-vulkan repos |
| Real-ESRGAN models | BSD-3-Clause | xinntao/Real-ESRGAN |
| Anime4K shaders | MIT | bloc97/Anime4K |
| animeJaNai model | see `animejanai-ncnn-vulkan/.../ATTRIBUTION.txt` | |
| 2x-sudo-UltraCompact | see `ATTRIBUTION.txt` in its directory | |
| 2x-NomosUni-SPAN | see `ATTRIBUTION.txt` in its directory | |
| W2xEX-ESRGAN models | see the HuggingFace repo linked above | |
| ncnn (compiled into `libwaifu2x-jni.so`) | BSD-3-Clause | Tencent/ncnn, built from source |
| **Qualcomm QNN runtime (`libQnnModelDlc.so`, `qnn-contexts/`)** | **Qualcomm AI Runtime SDK license** | Binary redistribution is permitted by the SDK terms with the application, but the terms must be reviewed before any public distribution; provenance is a second-hand extraction via MIU (see below) |

**Known caveat:** the QNN artifacts were not taken from Qualcomm directly but
extracted from MIU's release. Their integrity is covered by the checksums
below, but the legal review of Qualcomm's redistribution terms has not been
done. Do not publish builds containing them without that review.

## Verification

All bundled binaries and ncnn `.param` model files are checksummed in
[`upscale-assets-sha256sums.txt`](upscale-assets-sha256sums.txt) (paths
relative to `app/src/main/`). Verify and regenerate with:

```bash
cd app/src/main
sha256sum -c ../../../docs/upscale-assets-sha256sums.txt --quiet
# after replacing/adding any binary or .param file:
for f in <changed files>; do sha256sum -b "$f"; done >> ../../../docs/upscale-assets-sha256sums.txt
```

Bundled model files are extracted to the app cache dir on first use and
refreshed whenever `Waifu2x.BUNDLED_MODEL_CACHE_VERSION` is bumped.
