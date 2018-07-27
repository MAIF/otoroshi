FROM openjdk:8

LABEL maintainer "MAIF <oss@maif.fr>"

RUN groupadd -g 999 otoroshi && useradd -r -u 999 -g otoroshi otoroshi

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN apt-get update -y \
  && apt-get install -y curl build-essential zlib1g-dev \
  && wget https://dl.bintray.com/maif/binaries/otoroshi.jar/snapshot/otoroshi.jar \
  && wget https://github.com/oracle/graal/releases/download/vm-1.0.0-rc4/graalvm-ce-1.0.0-rc4-linux-amd64.tar.gz \
  && tar -xvf graalvm-ce-1.0.0-rc4-linux-amd64.tar.gz \
  && mv graalvm-1.0.0-rc4 graalvm \
  && mkdir /otoroshi/leveldb \
  && mkdir /otoroshi/imports \
  && chown -R otoroshi:otoroshi /otoroshi \
  && chown -R otoroshi:otoroshi /graalvm 

VOLUME /otoroshi/imports
VOLUME /otoroshi/leveldb

RUN /otoroshi/graalvm/bin/native-image -H:+ReportUnsupportedElementsAtRuntime --verbose -jar otoroshi.jar 

ENTRYPOINT ["./entrypoint.sh"]

USER otoroshi

EXPOSE 8080

CMD [""]