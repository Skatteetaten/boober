#!/usr/bin/env bash

host=http://boober-aos-bas-dev.utv.paas.skead.no
#host=:8080
token=$(oc whoami -t)
#http --timeout 300 PUT :8080/setup Authorization:"bearer $token"  < files.json
http --timeout 300 PUT $host/auroraconfig/paas Authorization:"bearer $token"  < auroraconfig.json
http --timeout 300 GET $host/auroraconfig/paas Authorization:"bearer $token"

