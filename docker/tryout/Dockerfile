# Not for a production usage, just a simple playground to show what you can do with Otoroshi
FROM openjdk:8u151-jre-alpine

MAINTAINER MAIF <oss@maif.fr>

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN wget 'https://dl.bintray.com/maif/binaries/otoroshi.jar/latest/otoroshi.jar' \
  && mkdir ./leveldb

VOLUME ./leveldb

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD [""]