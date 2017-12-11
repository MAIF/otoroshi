FROM openjdk:8

MAINTAINER MAIF <oss@maif.fr>

ENV APP_NAME otoroshi
ENV APP_VERSION 1.0.0
ENV NVM_VERSION 0.33.2
ENV NODE_VERSION 8.6.0
ENV SBT_VERSION 0.13.15

# Install Stuff

WORKDIR /opt

RUN wget --quiet https://dl.bintray.com/sbt/native-packages/sbt/${SBT_VERSION}/sbt-${SBT_VERSION}.zip \
  && unzip sbt-${SBT_VERSION}.zip \
  && mkdir -p /root/.sbt/0.13/plugins \
  && touch /root/.sbt/0.13/plugins/build.sbt \
  && echo 'addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC8")' >> /root/.sbt/0.13/plugins/plugins.sbt

ENV PATH /opt/sbt/bin:$PATH

RUN wget -qO- https://raw.githubusercontent.com/creationix/nvm/v${NVM_VERSION}/install.sh | bash \
  && export NVM_DIR="/root/.nvm" \
  && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" \
  && nvm install ${NODE_VERSION} \
  && nvm use ${NODE_VERSION} \
  && chmod -R 777 /root/.nvm

ENV PATH /root/.nvm/versions/node/v${NODE_VERSION}/bin:$PATH

RUN curl -o- -L https://yarnpkg.com/install.sh | bash

# Copy sources

RUN mkdir -p /usr/src/app/sources \
 && mkdir -p /usr/src/app/dist

RUN git clone https://github.com/MAIF/otoroshi.git /usr/src/app/sources --depth=1

# Build the APP

WORKDIR /usr/src/app/sources/otoroshi/javascript

RUN export NVM_DIR="/root/.nvm" \
  && export PATH="/root/.yarn/bin:$PATH" \
  && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" \
  && npm config set user 0 \
  && npm config set unsafe-perm true \
  && yarn install \
  && yarn run build \
  && rm -rf ./node_modules \
  && rm -rf /root/.nvm \
  && rm -rf /root/.yarn \
  && rm -rf /root/.yarn-cache \
  && rm -rf /root/.yarn-configDock

WORKDIR /usr/src/app/sources/otoroshi

RUN sbt dist \
  && unzip ./target/universal/${APP_NAME}-${APP_VERSION}.zip -d /usr/src/app/dist \
  && chmod +x /usr/src/app/dist/${APP_NAME}-${APP_VERSION}/bin/${APP_NAME} \
  && mv ./entrypoint.sh /usr/src/app/dist/${APP_NAME}-${APP_VERSION} \
  && chmod +x /usr/src/app/dist/${APP_NAME}-${APP_VERSION}/entrypoint.sh \
  && rm -rf /root/.ivy2 \
  && rm -rf /root/.sbt \
  && rm -rf /root/.coursier

WORKDIR /usr/src/app/dist/${APP_NAME}-${APP_VERSION}

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD 