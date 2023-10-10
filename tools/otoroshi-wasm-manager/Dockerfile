FROM node:current-slim as node

WORKDIR /usr/src/app

COPY ui ./ui
COPY server ./server

WORKDIR /usr/src/app/ui
RUN npm install
RUN npm run build

WORKDIR /usr/src/app/server
RUN npm install

FROM ubuntu:22.04

# Install Node
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/include/node /usr/local/include/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
# Install yarn
COPY --from=node /opt/yarn-v*/bin/* /usr/local/bin/
COPY --from=node /opt/yarn-v*/lib/* /usr/local/lib/
# Link npm and yarn
RUN ln -vs /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -vs /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

WORKDIR /code

ENV DEBIAN_FRONTEND=noninteractive 

RUN apt-get update -y 
RUN apt-get install -y build-essential git curl software-properties-common
RUN apt-get install -y --no-install-recommends tzdata golang-1.20 binaryen

ENV PATH="/usr/lib/go-1.20/bin:${PATH}"

RUN go install github.com/extism/cli/extism@latest

# Get rust
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- --profile=minimal -y

ENV PATH="/root/.cargo/bin:${PATH}"
RUN rustup target add wasm32-unknown-unknown

RUN apt-get install -y wget
RUN wget https://github.com/tinygo-org/tinygo/releases/download/v0.27.0/tinygo_0.27.0_amd64.deb
RUN dpkg -i tinygo_0.27.0_amd64.deb

RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-x86_64-linux-v0.3.4.gz"
RUN gunzip extism-js*.gz
RUN mv extism-js-* /usr/local/bin/extism-js
RUN chmod +x /usr/local/bin/extism-js

RUN curl https://get.wasmer.io -sSfL | sh

RUN curl -L -o opa https://openpolicyagent.org/downloads/v0.50.2/opa_linux_amd64_static
RUN chmod 755 ./opa
RUN mv opa /usr/local/bin

RUN npm install pm2@latest -g

RUN mkdir /server
COPY --from=node /usr/src/app/ui/build /ui/build
COPY --from=node /usr/src/app/server /server

WORKDIR /server
EXPOSE 5001
CMD ["pm2-runtime", "index.js"]


# FROM node:current-slim as node

# WORKDIR /usr/src/app

# COPY ui ./ui
# COPY server ./server

# WORKDIR /usr/src/app/ui
# RUN npm install
# RUN npm run build

# WORKDIR /usr/src/app/server
# RUN npm install

# FROM golang:1.21.2-alpine3.18 AS go

# ENV DEBIAN_FRONTEND=noninteractive

# RUN apk add --update --no-cache git go wget

# ENV PATH="/usr/lib/go-1.20/bin:${PATH}"

# RUN go install github.com/extism/cli/extism@latest

# ENV TINYGO_RELEASE=0.30.0
# RUN wget https://github.com/tinygo-org/tinygo/releases/download/v${TINYGO_RELEASE}/tinygo${TINYGO_RELEASE}.linux-amd64.tar.gz && \
#     tar xfv tinygo${TINYGO_RELEASE}.linux-amd64.tar.gz -C /usr/local && \
#     rm tinygo${TINYGO_RELEASE}.linux-amd64.tar.gz
# ENV PATH=${PATH}:/usr/local/tinygo/bin

# FROM ubuntu:22.04

# RUN apt-get update -y
# RUN apt-get install -y curl binaryen git

# # # Copy extim-js
# # COPY --from=go /usr/local/bin/extism-js /usr/local/bin/extism-js

# # Copy go
# COPY --from=go /usr/local/go/ /usr/local/go/
# ENV PATH="/usr/local/go/bin:${PATH}"
# ENV GOROOT="/usr/local/go/bin"
# ENV GOPROXY="direct"

# # Copy tinygo
# COPY --from=go /usr/local/tinygo/ /usr/local/tinygo/
# ENV PATH="/usr/local/tinygo/bin:${PATH}"

# # Install Node
# COPY --from=node /usr/local/bin/node /usr/local/bin/node
# COPY --from=node /usr/local/include/node /usr/local/include/node
# COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
# # Install yarn
# COPY --from=node /opt/yarn-v*/bin/* /usr/local/bin/
# COPY --from=node /opt/yarn-v*/lib/* /usr/local/lib/
# # Link npm and yarn
# RUN ln -vs /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
#     && ln -vs /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- --profile=minimal -y

# ENV PATH="/root/.cargo/bin:${PATH}"
# RUN rustup target add wasm32-unknown-unknown

# RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-x86_64-linux-v0.3.4.gz"
# RUN gunzip extism-js*.gz
# RUN mv extism-js-* /usr/local/bin/extism-js
# RUN chmod +x /usr/local/bin/extism-js

# RUN curl https://get.wasmer.io -sSfL | sh

# RUN curl -L -o opa https://openpolicyagent.org/downloads/v0.50.2/opa_linux_amd64_static
# RUN chmod 755 ./opa
# RUN mv opa /usr/local/bin

# RUN npm install pm2@latest -g

# RUN mkdir /server
# COPY --from=node /usr/src/app/ui/build /ui/build
# COPY --from=node /usr/src/app/server /server

# WORKDIR /server
# EXPOSE 5001
# CMD ["node", "index.js"]