package services

class BooberConfigFiles {

  def BooberConfigFiles = """
{
 "flags": [
    "cert"
  ],
  "groups": "APP_PaaS_drift APP_Paas_utv",
  "affiliation": "mfp"
}
"""

  def globalApp = """
{
  "flags": [
    "rolling",
    "route"
  ],
  "build": {
    "GROUP_ID": "ske.aurora.openshift.demo",
    "ARTIFACT_ID": "aos-features",
    "VERSION": "1.0.5"
  },
  "deploy": {
    "SPLUNK_INDEX": "openshift-test",
    "MAX_MEMORY": "512Mi"
  }
}
"""

  def environmentAbout = """
{
  "users": "k77319",
  "type": "development",
  "cluster": "prod"
}
"""

  def environmentApp = """
{
  "build": {
    "VERSION": "1.0.6-SNAPSHOT"
  },
  "deploy": {
    "DATABASE": "demo:5bfe8be8-cc73-4882-ab05-212ddbd10632"
  },
  "config": {
    "DEMO_PROPERTY": "ELVIS ER DØD"
  },
  "secretFolder": "/tmp/secret-utv"
}
"""
}
