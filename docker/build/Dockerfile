FROM openjdk:8u151-jre-alpine

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN apk update \
  && apk upgrade \
  && apk add bash \
  && unzip ./otoroshi-dist.zip \
  && mv otoroshi-1.0.2 otoroshi \
  && chmod +x otoroshi/bin/otoroshi \
  && mkdir otoroshi/leveldb

VOLUME /usr/app/otoroshi/leveldb
VOLUME /usr/app/otoroshi/conf

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD [""]