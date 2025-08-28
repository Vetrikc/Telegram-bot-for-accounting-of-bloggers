# Используем лёгкий образ с Java 17 (можно заменить на нужный)
FROM openjdk:11-jdk-slim

# Указываем переменную для JAR-файла
ARG JAR_FILE=target/springbot-0.0.1.jar

# Копируем JAR-файл в контейнер
COPY ${JAR_FILE} app.jar

# Открываем порт (опционально, для инфы)
EXPOSE 8080

VOLUME:

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "/app.jar"]

