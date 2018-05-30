FROM ubuntu

RUN mkdir -p /client
WORKDIR /client

COPY . /client

RUN apt-get update && apt-get install build-essential libssl-dev git -y
RUN git clone https://github.com/wg/wrk.git wrk
RUN cd wrk && make && cp wrk /usr/local/bin

CMD [ "/bin/sh", "client.sh" ]
