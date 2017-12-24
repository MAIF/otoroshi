FROM rust:1.20

MAINTAINER MAIF <oss@maif.fr>

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN wget https://dl.bintray.com/maif/binaries/linux-otoroshicli/latest/otoroshicli \
  && wget https://raw.githubusercontent.com/MAIF/otoroshi/master/clients/cli/otoroshicli.toml \
  && chmod +x otoroshicli 

VOLUME /usr/app

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]