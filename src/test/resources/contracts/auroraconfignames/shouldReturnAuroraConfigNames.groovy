package auroraconfignames

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/auroraconfignames'
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/auroraconfignames.json'))
  }
}