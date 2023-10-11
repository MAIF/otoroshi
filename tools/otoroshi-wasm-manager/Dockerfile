FROM ubuntu:22.04

WORKDIR /code

ENV DEBIAN_FRONTEND=noninteractive 

RUN apt-get update -y 
RUN apt-get install -y build-essential git curl software-properties-common ca-certificates gnupg golang-1.20 wget

# install node
RUN mkdir -p /etc/apt/keyrings
RUN curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
RUN echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_18.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list
RUN apt-get update -y 
RUN apt-get install -y nodejs 

ENV PATH="/usr/lib/go-1.20/bin:${PATH}"
RUN go install github.com/extism/cli/extism@latest

# RUN python3.9 -m pip install --upgrade pip
RUN curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && python3 get-pip.py
RUN echo 'export PATH=~/.local/bin/:$PATH' >> ~/.bashrc

# Get rust
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- --profile=minimal -y

ENV PATH="/root/.cargo/bin:${PATH}"
# Add wasm-unknown-unknown target
RUN rustup target add wasm32-unknown-unknown

RUN wget https://github.com/tinygo-org/tinygo/releases/download/v0.27.0/tinygo_0.27.0_amd64.deb
RUN dpkg -i tinygo_0.27.0_amd64.deb

# RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-aarch64-linux-v0.3.4.gz"
RUN curl -L -O "https://github.com/extism/js-pdk/releases/download/v0.3.4/extism-js-x86_64-linux-v0.3.4.gz"
RUN gunzip extism-js*.gz
RUN mv extism-js-* /usr/local/bin/extism-js
RUN chmod +x /usr/local/bin/extism-js

RUN apt-get install binaryen

RUN curl https://get.wasmer.io -sSfL | sh

RUN curl -L -o opa https://openpolicyagent.org/downloads/v0.50.2/opa_linux_amd64_static
RUN chmod 755 ./opa
RUN mv opa /usr/local/bin

ADD ui $HOME/ui
ADD server $HOME/server

# install ui
WORKDIR $HOME/ui
#RUN npm install
#RUN npm run build
#RUN rm -rf node_modules

WORKDIR $HOME/server
RUN npm install pm2@latest -g
RUN npm install

EXPOSE 5001
CMD ["pm2-runtime", "index.js"]