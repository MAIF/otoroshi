FROM rust:1.20

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN wget https://dl.bintray.com/maif/binaries/linux-otoroshicli/latest/otoroshicli \
  && chmod +x otoroshicli 

VOLUME /usr/app

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]