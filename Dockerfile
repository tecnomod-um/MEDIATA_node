FROM openjdk:17-jdk-alpine
WORKDIR /app
VOLUME /opt/shared-folder
COPY target/TANIWHA_Backend_node.jar /app/TANIWHA_Backend_node.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/TANIWHA_Backend_node.jar"]
CMD ["--PORT=8081", "--NODE_IP=http://localhost", "--NAME=SCUBA", "--DESC=This is the description for the new node", "--COLOR=#21c2c3"]
