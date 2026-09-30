# BI Backend

BI analytics backend built with Java 21 and Spring Boot 3.5.

## Requirements

- Java 21
- Maven 3.9.16 (or the included Maven Wrapper)
- MySQL、Redis、RabbitMQ
- Chart generation requires the `DEEPSEEK_API_KEY` environment variable.
- Configure database credentials with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.

## Docker Compose

Docker Compose starts the MySQL, Redis, and RabbitMQ services required by the project. First, copy the environment template:

```bash
cp .env.example .env
```

Set local passwords in `.env`, then start the supporting services:

```bash
docker compose up -d
```

The database runs `sql/create_table.sql` when its Docker volume is first created. RabbitMQ Management is available at `http://localhost:15673`.

To run the backend in Compose as well, set `DEEPSEEK_API_KEY` and run:

```bash
docker compose --profile app up --build
```

For a local backend run, set `DB_PASSWORD`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, and `DEEPSEEK_API_KEY` to the values in `.env`, and set `RABBITMQ_PORT=5673`. The backend automatically uses RabbitMQ's internal port 5672 when running in a container.

```bash
./mvnw spring-boot:run
```

The service is available by default at `http://localhost:8101/api`, and the OpenAPI UI is at `http://localhost:8101/api/swagger-ui.html`.

## Build

```bash
./mvnw clean package
```
