FROM debian:jessie
RUN mkdir -p /client
WORKDIR /client
COPY . /client
RUN apt-get update -y && apt-get install build-essential libssl-dev git -y
RUN git clone https://github.com/giltene/wrk2.git wrk2
RUN cd wrk2 && make && cp wrk /usr/local/bin
CMD [ "/bin/sh", "client.sh" ]