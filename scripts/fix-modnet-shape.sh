#!/bin/sh
# Pins MODNet's dynamic ONNX input to 1x3x1024x576 so OpenCV 4.10 can import it.
# Usage: scripts/fix-modnet-shape.sh <dynamic-export.onnx> <fixed-output.onnx>
# Runs onnxsim in a throwaway Python container; afterwards update MODNET_SHA256 in the Dockerfile.
set -eu

[ $# -eq 2 ] || { echo "Usage: $0 <dynamic-export.onnx> <fixed-output.onnx>" >&2; exit 1; }
source_dir=$(cd "$(dirname "$1")" && pwd)
target_dir=$(mkdir -p "$(dirname "$2")" && cd "$(dirname "$2")" && pwd)

docker run --rm -v "$source_dir:/in:ro" -v "$target_dir:/out" python:3.11-slim sh -c "
  pip install -q --no-cache-dir --root-user-action=ignore onnx onnxsim onnxruntime &&
  onnxsim '/in/$(basename "$1")' '/out/$(basename "$2")' --overwrite-input-shape input:1,3,1024,576"

echo "MODNET_SHA256=$(shasum -a 256 "$2" | cut -d' ' -f1)"
