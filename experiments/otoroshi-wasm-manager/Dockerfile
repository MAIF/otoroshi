from ubuntu:20.04

RUN apt-get update -y
RUN apt-get install -y build-essential
RUN apt-get install -y git
RUN apt-get install -y curl
RUN curl -sL https://deb.nodesource.com/setup_16.x | bash
RUN apt-get install -y nodejs
RUN node -v

ENV DEBIAN_FRONTEND=noninteractive 
RUN apt-get install -y --no-install-recommends tzdata
RUN apt-get update -y
RUN apt-get install -y python3.9-distutils
RUN apt-get install -y python3.9 python3.9-dev
RUN apt-get install python3.9-distutils

# RUN python3.9 -m pip install --upgrade pip
RUN curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py
RUN python3.9 get-pip.py
RUN echo 'export PATH=~/.local/bin/:$PATH' >> ~/.bashrc

RUN pip3 install poetry
RUN pip3 install git+https://github.com/extism/cli
RUN extism install latest

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


