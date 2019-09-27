FROM openjdk:8

LABEL maintainer "MAIF <oss@maif.fr>"

RUN groupadd -g 999 otoroshi && useradd -r -u 999 -g otoroshi otoroshi

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN apt-get update -y \
  && apt-get install  -y curl \
  && curl -L -o otoroshi.jar https://github.com/MAIF/otoroshi/releases/download/v1.4.12-dev/otoroshi.jar \ 
  && curl -L -o otoroshicli https://github.com/MAIF/otoroshi/releases/download/v1.4.12-dev/otoroshicli \
  && chmod +x otoroshicli \
  && chown -R otoroshi:otoroshi /otoroshi

USER otoroshi

CMD [""]