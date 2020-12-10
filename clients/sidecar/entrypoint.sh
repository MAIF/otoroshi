#!/bin/sh

# export NVM_DIR="/root/.nvm" 
# . "$NVM_DIR/nvm.sh" 
# nvm install 14
# nvm use 14

# node src/sidecar.js "$@" 
pm2 start src/sidecar.js -i 2 --time --log sidecar.log -- "$@" 
pm2 logs