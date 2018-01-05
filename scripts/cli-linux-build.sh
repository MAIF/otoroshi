#!/bin/bash

LOCATION=`pwd`
cd $LOCATION/clients/cli
rm -rf ./target
docker run --rm --user "$(id -u)":"$(id -g)" -v "$PWD":/usr/src/myapp -w /usr/src/myapp rust:1.20 cargo build --release

