#!/usr/bin/env bash

which git || ( echo "Please install git" && sudo apt-get install git)

GIT_FOLDER=/tmp/boobergit/paas
[[ -d $GIT_FOLDER ]] || (mkdir -p $GIT_FOLDER && git init --bare $GIT_FOLDER)