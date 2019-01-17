package sts

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'POST'
    url '/v1/sts'
    headers {
      contentType(applicationJson())
    }
    body(
        '''{"name":"name","namespace":"namespace","affiliation":"affiliation","commonName":"commonName","ownerReference":{}}'''
    )
    bodyMatchers {
      jsonPath('$.name', byRegex(nonEmpty()))
      jsonPath('$.namespace', byRegex(nonEmpty()))
      jsonPath('$.affiliation', byRegex(nonEmpty()))
      jsonPath('$.commonName', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/openShiftResponses.json'))
  }
}