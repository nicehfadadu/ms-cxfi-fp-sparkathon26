FROM public.ecr.aws/amazoncorretto/amazoncorretto:17

WORKDIR /app
COPY target/ms-cxfi-fp-sparkathon26-1.0.0-SNAPSHOT.jar app.jar

ENV AWS_REGION=us-east-1
EXPOSE 8091

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
