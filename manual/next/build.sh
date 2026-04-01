#!/bin/bash

nvm use 24
npm run build
npm run build-root
rm -rf ../../docs/manual
rm -rf ../../docs/manual-root
cp -r build ../../docs/manual
cp -r build-root ../../docs/manual-root
