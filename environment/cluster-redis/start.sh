#!/bin/bash

# Start containers
docker-compose -f environment/cluster-redis/docker-compose-cluster.yml up
echo "[TipsGO]: Redis_sentinel server start..."