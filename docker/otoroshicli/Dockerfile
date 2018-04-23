FROM rust:1.23

LABEL maintainer "MAIF <oss@maif.fr>"

RUN groupadd -g 999 otoroshi && useradd -r -u 999 -g otoroshi otoroshi

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN chmod +x otoroshicli 

VOLUME /otoroshi

USER otoroshi

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]