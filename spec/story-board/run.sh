#!/bin/sh

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" &> /dev/null && pwd)

python3 -m http.server 6789 -d $SCRIPT_DIR
