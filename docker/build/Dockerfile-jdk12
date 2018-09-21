FROM openjdk:12

LABEL maintainer "MAIF <oss@maif.fr>"

RUN groupadd -g 999 otoroshi && useradd -r -u 999 -g otoroshi otoroshi

RUN mkdir -p /usr/app/otoroshi

WORKDIR /usr/app

COPY ./otoroshi /usr/app/otoroshi
COPY ./entrypoint.sh /usr/app/

RUN chown -R otoroshi:otoroshi /usr/app/otoroshi

VOLUME /usr/app/otoroshi/imports
VOLUME /usr/app/otoroshi/leveldb
VOLUME /usr/app/otoroshi/conf
VOLUME /usr/app/otoroshi

ENTRYPOINT ["./entrypoint.sh"]

USER otoroshi

EXPOSE 8080

STOPSIGNAL SIGINT

CMD [""]