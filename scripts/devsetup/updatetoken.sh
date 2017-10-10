#!/usr/bin/env bash

oc whoami -t || (echo "You must be logged into openshift" && exit 1)
TOKEN=$(oc whoami -t)
echo $TOKEN | sudo tee /opt/boober