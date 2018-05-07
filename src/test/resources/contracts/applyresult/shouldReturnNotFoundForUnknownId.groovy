package applyresult

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/apply-result/aos/invalid-id'
  }
  response {
    status 404
  }
}
