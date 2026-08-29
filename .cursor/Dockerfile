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

# ubuntu:24.04 ships a non-root `ubuntu` user (uid 1000). Grant only the
# passwordless sudo start.sh needs: pg_ctlcluster as root, pg_isready/psql as
# the postgres OS user (Ubuntu 24.04 paths).
RUN printf '%s\n' \
      'ubuntu ALL=(root) NOPASSWD: /usr/bin/pg_ctlcluster' \
      'ubuntu ALL=(postgres) NOPASSWD: /usr/bin/pg_isready, /usr/bin/psql' \
      > /etc/sudoers.d/ubuntu \
    && chmod 0440 /etc/sudoers.d/ubuntu

# Local-only development database credentials — never used in production, where
# these come from real secrets. application.properties defaults are username
# kevinturner / empty password; this image OVERRIDES them to kjv/kjv for the
# Cloud Agent role created by start.sh.
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
