#!/bin/bash
#
# Script to run Emscripten tools from within the emsdk repo. Pass in command
# line args that you want to run. The first arg should be the name of the tool
# you want to run, e.g. "em++".

repo_rel=${BASH_SOURCE[0]}.runfiles/emsdk
repo=$(python -c '
import sys
import os.path
print(os.path.abspath(sys.argv[1]))' "$repo_rel")

# Set up the required environment variables. This is equivalent to running:
#   source "$repo/emsdk_env.sh"
export EM_PATH="$repo"
export EM_PATH="$EM_PATH:$repo/fastcomp/emscripten"
export EM_PATH="$EM_PATH:$repo/node/12.9.1_64bit/bin"
# Note: Our path must come before $PATH to ensure we pick up the local emsdk
export PATH="$EM_PATH:$PATH"
export EMSDK="$repo"
export EM_CONFIG="$repo/.emscripten"
export EMSDK_NODE="$repo/node/12.9.1_64bit/bin/node"

# Use a different location for emscripten's cache folder. The default one, which
# is inside the emsdk folder, is read-only.
export EM_CACHE="$PWD/bazel_emscripten"

# Run the command line args that were passed to this script.
exec "$@"
