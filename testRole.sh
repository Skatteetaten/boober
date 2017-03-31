#!/usr/bin/env bash

token=$(oc whoami -t)
http GET :8080/role Authentication:"bearer $token"

