package applyresult

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/apply-result/aos/234'
  }
  response {
    status 404
  }
}
