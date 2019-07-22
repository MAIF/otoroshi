FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

RUN apt-get update -y \
  && apt-get install -y curl bash wget \
  && curl -L -o otoroshi.jar https://github.com/MAIF/otoroshi/releases/download/v1.4.10/otoroshi.jar

EXPOSE 8091

CMD ["java", "-Dhttp.port=8091", "-Dapp.importFrom=http://backend:8100/otoroshi.json", "-jar", "otoroshi.jar" ]