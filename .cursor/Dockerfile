# Cloud Agent development image for the KJV Bible Reader.
# Provides Java 21, Maven, and a local PostgreSQL 16 server so the Spring Boot
# app (which uses Flyway migrations + JPA `validate`) can run end-to-end.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jdk \
        maven \
        postgresql \
        postgresql-contrib \
        git \
        curl \
        ca-certificates \
        sudo \
        tzdata \
    && rm -rf /var/lib/apt/lists/*

# ubuntu:24.04 ships a non-root `ubuntu` user (uid 1000). Grant it passwordless
# sudo so the per-boot bootstrap can start and provision the local PostgreSQL
# cluster (which is owned by the `postgres` system user).
RUN echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu \
    && chmod 0440 /etc/sudoers.d/ubuntu

# Local-only development database credentials — never used in production, where
# these come from real secrets. They line up with the defaults consumed by
# src/main/resources/application.properties.
ENV KJV_DB_HOST=localhost \
    KJV_DB_PORT=5432 \
    KJV_DB_NAME=readthekjv \
    KJV_DB_USERNAME=kjv \
    KJV_DB_PASSWORD=kjv \
    KJV_DB_SSLMODE=disable \
    SPRING_PROFILES_ACTIVE=dev \
    X_ENABLED=false \
    VOTD_ENABLED=false

USER ubuntu
