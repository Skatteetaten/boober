#!/usr/bin/env bash

token=$(oc whoami -t)
http PUT :8080/setup Authorization:"bearer $token"  < files.json

