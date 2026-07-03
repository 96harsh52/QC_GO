#!/usr/bin/env bash
# Run any command inside the project's TF venv with CUDA libs on the loader path.
# TF 2.16 (pip [and-cuda]) does not auto-add the nvidia/*/lib dirs, so we do it here.
# Usage: ./run_gpu.sh python train_classifier.py --data ./data ...
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/.venv"
NVIDIA_DIR="$($VENV/bin/python -c 'import nvidia,os;print(os.path.dirname(nvidia.__file__))')"
export LD_LIBRARY_PATH="$(find "$NVIDIA_DIR" -name lib -type d | tr '\n' ':')${LD_LIBRARY_PATH:-}"
# ptxas (needed by XLA/JIT) lives in the cuda_nvcc wheel:
export PATH="$NVIDIA_DIR/cuda_nvcc/bin:$PATH"
export TF_CPP_MIN_LOG_LEVEL="${TF_CPP_MIN_LOG_LEVEL:-1}"
exec "$VENV/bin/$@"
