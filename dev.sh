#!/bin/bash
error_exit() {
  echo "$1"
  exit 1
}

name=$1
oc get bc "$name" &> /dev/null || error_exit  "Build Config $1 does not exist"

./gradlew build -x test

leveransepakke=$(find build/distributions -type f -name "*-Leveransepakke.zip")

echo "Start OpenShift binary build"
oc start-build $name --from-file=$leveransepakke --follow --wait

which stern &> /dev/null && echo "Tail logs with stern $name" && stern $name
