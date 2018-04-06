package clientconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/clientconfig'
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/clientconfig.json'))
  }
}