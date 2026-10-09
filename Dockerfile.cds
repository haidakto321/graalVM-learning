# CDS (Class Data Sharing): normal JVM + an archive of already-parsed classes.
# Same build stage as Dockerfile.jvm (Docker reuses the cached layers).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /work
COPY --from=build /src/target/app.jar app.jar
# CDS cannot read classes from jars nested inside the fat jar, so unpack it:
# application/app.jar + application/lib/*.jar (Spring Boot 3.3+ "tools" jar mode).
RUN java -Djarmode=tools -jar app.jar extract --destination application && rm app.jar
# Training run at BUILD time: start the app, exit right after the Spring context is ready,
# and dump every class it loaded into app.jsa.
RUN java -XX:ArchiveClassesAtExit=application/app.jsa -Dspring.context.exit=onRefresh -jar application/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=application/app.jsa", "-jar", "application/app.jar"]
