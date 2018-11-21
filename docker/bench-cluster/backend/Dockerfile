FROM node

RUN mkdir -p /backend
WORKDIR /backend

COPY . /backend

RUN apt-get update && apt-get install apt-transport-https
RUN curl -sS https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add -
RUN echo "deb https://dl.yarnpkg.com/debian/ stable main" | tee /etc/apt/sources.list.d/yarn.list
RUN apt-get update && apt-get install yarn
RUN yarn install

EXPOSE 8040-8079
EXPOSE 8100

CMD [ "yarn", "start" ]