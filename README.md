# Advertising accounting Telegram bot

A small internal Telegram bot I built as a paid freelance project for managing bloggers and advertising placements.

The bot was used by a small team, so the focus was not high load or complex architecture. The goal was to replace manual tracking with a simple workflow inside Telegram.

## What it does

- keeps a list of bloggers and their channels;
- tracks available advertising points;
- stores links to published videos/posts;
- supports admin and owner roles;
- includes pagination for the blogger list;
- sends a weekly notification when a blogger is running low on points;
- synchronizes data with Google Sheets.

The bot uses long polling and stores its main data in MySQL.

## Stack

Java 11, Spring Boot, Spring Data JPA, MySQL, Telegram Bots API, Google Sheets API and Docker.

## Running locally

The project itself is inside the `Telegram Bot/` directory.

Create the Docker environment file:

```bash
cp .env.example .env
```

Create the application configuration:

```bash
cp src/main/resources/application.example.properties src/main/resources/application.properties
```

Then fill in your Telegram bot token, owner id and database credentials.

Google Sheets synchronization also requires a service-account credentials file at:

```
src/main/resources/credentials.json
```

Start the database and application with:

```bash
docker compose up --build
```

## Project status

The project is no longer actively developed. I keep it public as one of the freelance systems I delivered while learning and working with Java/Spring Boot.
