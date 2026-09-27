# Local segmentation models

The production engine uses `modnet_photographic.onnx`, which is committed so
Docker builds are reproducible. `u2net_human_seg.onnx` is only a local
development fallback and stays out of Git.

`modnet_photographic.onnx` is an ONNX-simplified MODNet model with a fixed
`input` shape of `1×3×1024×576` (the engine always feeds 576×1024 frames).
The PyTorch export has a dynamic `[batch_size, 3, height, width]` input, which
OpenCV 4.10 (the version in the Docker image) cannot import: it fails at
`/hr_branch/Concat_1` with "Inconsistent shape for ConcatLayer". To regenerate
the fixed model from such an export, run:

```sh
scripts/fix-modnet-shape.sh path/to/dynamic-export.onnx models/modnet_photographic.onnx
```

Docker verifies the model's SHA-256 (`MODNET_SHA256` in the Dockerfile) during
image creation, so update that value whenever the model changes.
