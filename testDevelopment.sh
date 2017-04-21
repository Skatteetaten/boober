#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PUT :8080/auroraconfig/paas Authorization:"bearer $token"  < filesDevelopment.json
