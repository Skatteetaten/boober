#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PUT :8080/setup-dryrun Authorization:"bearer $token"  < files.json

