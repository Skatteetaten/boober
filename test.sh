#!/usr/bin/env bash

token=$(oc whoami -t)
http PUT :8080/setup Authentication:"bearer $token"  < files.json

