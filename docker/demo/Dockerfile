FROM openjdk:8

LABEL maintainer "MAIF <oss@maif.fr>"

RUN groupadd -g 999 otoroshi && useradd -r -u 999 -g otoroshi otoroshi

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN apt-get update -y \
  && apt-get install  -y curl \
  && wget https://dl.bintray.com/maif/binaries/otoroshi.jar/1.4.9-dev/otoroshi.jar \
  && wget https://dl.bintray.com/maif/binaries/linux-otoroshicli/1.4.9-dev/otoroshicli \
  && chmod +x otoroshicli \
  && chown -R otoroshi:otoroshi /otoroshi

USER otoroshi

CMD [""]