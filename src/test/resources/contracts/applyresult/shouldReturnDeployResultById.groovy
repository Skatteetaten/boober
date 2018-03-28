package applyresult

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/apply-result/aos/123'
  }
  response {
    status 200
    body(file('deployresult-response.json'))
    headers {
      contentType(applicationJson())
    }
  }
}
