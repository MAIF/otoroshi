FROM openjdk:8u151-jre-alpine

RUN mkdir -p /usr/app \
  && cd /usr/app \ 
  && wget https://dl.bintray.com/maif/binaries/linux-otoroshicli/latest/otoroshicli \
  && wget https://github.com/MAIF/otoroshi/edit/master/clients/cli/otoroshicli.toml \
  && chmod +x ./otoroshicli \
  && pwd \
  && ls -ahl

VOLUME /usr/app

WORKDIR /usr/app

COPY . /usr/app

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]