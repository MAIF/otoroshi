FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

# RUN apt-get update -y \
#   && apt-get install -y curl bash wget \
#   && wget https://dl.bintray.com/maif/binaries/otoroshi.jar/snapshot/otoroshi.jar 

EXPOSE 8091

CMD ["java", "-Dhttp.port=8091", "-Dotoroshi.cluster.mode=leader", "-Dapp.importFrom=http://backend:8100/otoroshi.json", "-jar", "otoroshi.jar" ]