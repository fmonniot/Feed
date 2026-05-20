#!/usr/bin/env bash

# One-time setup: create a local config from the template
mkdir -p data
cp server/config.docker.example.toml data/config.toml

# Run the container
docker run --rm \
  --name feed \
  -p 3333:3000 \
  -v "$(pwd)/data/config.toml:/app/config.toml:ro" \
  -v feed_data:/app/data \
  -e FEED_JWT_SECRET="$(openssl rand -base64 32)" \
  feed-server:0.0.0-dev
