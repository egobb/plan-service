COMPOSE = docker compose -f deploy/docker-compose.yml
MVN = bash ./mvnw -f app/pom.xml

# Defaults for local scaling
API ?= 2
PROCESS ?= 2

.PHONY: help up down logs ps run run-scaled run-api db stop test fmt lint build docker-build docker-run

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | awk 'BEGIN {FS=":.*##"} {printf "%-15s %s\n", $$1, $$2}'

run: ## Run full stack (db + api + workers) using docker compose
	$(COMPOSE) up -d --build

run-scaled: ## Run stack and scale api/worker-process locally (API=K PROCESS=N)
	$(COMPOSE) up -d --build --scale api=$(API) --scale worker-process=$(PROCESS)

run-api: ## Run API locally (requires DB running)
	$(MVN) -q -DskipTests spring-boot:run

db: ## Start only Postgres
	$(COMPOSE) up -d db

stop: ## Stop stack
	$(COMPOSE) down

up: ## Start the service with Docker Compose
	$(COMPOSE) up -d --build

down: ## Stop and remove containers
	$(COMPOSE) down -v

ps: ## List running containers
	$(COMPOSE) ps

logs: ## Tail logs
	$(COMPOSE) logs -f

build: ## Build jar (no tests)
	$(MVN) -q -B -DskipTests package

test: ## Run tests
	$(MVN) -q -B verify