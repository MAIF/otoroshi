FROM eclipse-temurin:17
RUN mkdir -p /usr/app
WORKDIR /usr/app
COPY otoroshi.jar /usr/app/otoroshi.jar
COPY entrypoint-jar.sh /usr/app/entrypoint-jar.sh
RUN chmod +x /usr/app/entrypoint-jar.sh
ENTRYPOINT ["/usr/app/entrypoint-jar.sh"]
EXPOSE 8080