FROM openjdk:8

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /usr/otoroshi

WORKDIR /usr/otoroshi

COPY . /usr/otoroshi

RUN apt-get update -y \
  && apt-get install  -y curl \
  && wget https://dl.bintray.com/maif/binaries/otoroshi.jar/1.1.1/otoroshi.jar \
  && wget https://dl.bintray.com/maif/binaries/linux-otoroshicli/1.1.1/otoroshicli \
  && chmod +x otoroshicli

CMD [""]