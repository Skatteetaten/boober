package clientconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/clientconfig'
  }
  response {
    status 200
    body(file('clientconfig-response.json'))
    headers {
      contentType(applicationJson())
    }
  }
}