FROM node:8.6.0

MAINTAINER MAIF <oss@maif.fr>

ENV APP_NAME otoroshi-elastic-connector
ENV APP_VERSION 1.0.0

# Install Yarn

WORKDIR /opt

RUN curl -o- -L https://yarnpkg.com/install.sh | bash

# Copy sources

RUN mkdir -p /usr/app/
COPY . /usr/app/
WORKDIR /usr/app/

# Install deps

RUN yarn install 

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD