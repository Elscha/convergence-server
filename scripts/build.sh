#!/bin/bash
sbt compile stage

mkdir target/docker
cp -a distribution/target/universal/stage target/docker
cp -a src/main/docker/* target/docker

docker build --no-cache -t elscha/convergence-server:latest target/docker