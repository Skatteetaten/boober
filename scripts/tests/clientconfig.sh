#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 GET :8080/clientconfig/ Authorization:"bearer $token"