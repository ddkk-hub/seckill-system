FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN command -v curl >/dev/null \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs && chown 10001:10001 /app/logs
COPY --chown=10001:10001 target/seckill-system-stage5.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/app.jar"]
