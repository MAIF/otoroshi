FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN apt-get update -y \
  && apt-get install -y curl bash build-essential zlib1g-dev \
  && wget https://github.com/oracle/graal/releases/download/vm-1.0.0-rc1/graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && tar -xvf graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && mv graalvm-1.0.0-rc1 graalvm 

EXPOSE 8091

CMD ["/heimdallr/graalvm/bin/java", "-Dhttp.port=8092", "-Dapp.importFrom=http://backend:8100/otoroshi.json", "-jar", "otoroshi.jar" ]
