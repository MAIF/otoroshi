FROM rust:1.23

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN chmod +x otoroshicli 

VOLUME /usr/app

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]