FROM ubuntu:22.04

WORKDIR /code

ENV DEBIAN_FRONTEND=noninteractive 

RUN apt-get update -y 
RUN apt-get install -y build-essential git curl software-properties-common
RUN curl -sL https://deb.nodesource.com/setup_16.x | bash
RUN apt-get install -y nodejs --no-install-recommends tzdata python3-distutils python3.9
# RUN apt-get update -y
# RUN apt-get install -y 

# RUN python3.9 -m pip install --upgrade pip
RUN curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && python3 get-pip.py
RUN echo 'export PATH=~/.local/bin/:$PATH' >> ~/.bashrc

RUN pip3 install poetry git+https://github.com/extism/cli
RUN extism install latest

# Get rust
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- --profile=minimal -y

ENV PATH="/root/.cargo/bin:${PATH}"
# Add wasm-unknown-unknown target
RUN rustup target add wasm32-unknown-unknown

RUN apt-get install -y golang-go wget
RUN wget https://github.com/tinygo-org/tinygo/releases/download/v0.27.0/tinygo_0.27.0_amd64.deb
RUN dpkg -i tinygo_0.27.0_amd64.deb

#RUN apt-get install -y libc6
# RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-aarch64-linux-v0.3.4.gz"
RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-x86_64-linux-v0.3.4.gz"
RUN gunzip extism-js*.gz
RUN mv extism-js-* /usr/local/bin/extism-js
RUN chmod +x /usr/local/bin/extism-js

RUN curl https://get.wasmer.io -sSfL | sh

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


