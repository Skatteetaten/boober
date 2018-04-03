package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url '/v1/auroraconfig/aurora-config-name/validate'
    headers {
      contentType(applicationJson())
    }
    body(name: 'name', files: [])
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/validate.json'))
  }
}