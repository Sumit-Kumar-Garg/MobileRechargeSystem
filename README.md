# Mobile Recharge System

[![backend-ci](https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem/actions/workflows/ci.yml)

A mobile recharge and top-up system. Customers link their phone numbers, browse prepaid plans, pay, and get the
top-up applied to their number.

This repository holds every part of the system, one folder per project.

## Projects
| Folder | What it is | Stack |
|---|---|---|
| [`backend/`](backend/README.md) | REST API: plans, orders, payments, top-up provisioning, refunds, notifications, AI advisor | Java 21, Spring Boot 3.3, PostgreSQL, Kafka, Redis |
| [`mobile/`](mobile/README.md) | Customer app for Android and iOS: screens and logic written once in a shared module | Kotlin Multiplatform, Compose Multiplatform, Ktor |

To add a project, create its own top-level folder with its own README, and add a CI
workflow under `.github/workflows/` that only runs when that folder changes.

## Getting started
Each project has its own setup instructions. For the backend:
```bash
git clone https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem.git
cd MobileRechargeSystem/backend
docker compose up -d
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
```
See [`backend/README.md`](backend/README.md) for the full guide. For the app, open `mobile/` in Android Studio and
follow [`mobile/README.md`](mobile/README.md).

## Repository layout
```
.github/      CI workflows and code ownership for all projects
backend/      the backend service
mobile/       the Android and iOS app (shared, androidApp, iosApp)
```

## License
No license has been chosen yet, so all rights are reserved by default.
