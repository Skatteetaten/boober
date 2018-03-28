package applyresult

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/apply-result/aos'
  }
  response {
    status 200
    body(file('deployhistory-response.json'))
    headers {
      contentType(applicationJson())
    }
  }
}