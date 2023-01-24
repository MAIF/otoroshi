from alpine:3.17.1

RUN apk --no-cache add bash
RUN apk --no-cache add curl
RUN apk --no-cache add --update nodejs npm
RUN apk --no-cache add build-base
RUN apk --no-cache add python3 py3-pip

# Get rust
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y

ENV PATH="/root/.cargo/bin:${PATH}"

# Add wasm-unknown-unknown target
RUN rustup target add wasm32-unknown-unknown

ADD ui $HOME/ui
ADD server $HOME/server

# install ui

WORKDIR $HOME/ui
RUN npm install
RUN npm run build
RUN rm -rf node_modules

WORKDIR $HOME/server
RUN npm install

EXPOSE 5001
CMD ["node", "index.js"]


