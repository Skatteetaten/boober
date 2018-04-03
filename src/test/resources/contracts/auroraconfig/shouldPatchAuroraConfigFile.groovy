package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PATCH'
    url '/v1/auroraconfig/aurora-config-name/file-name'
    headers {
      contentType(applicationJson())
    }
    body(
        content: 'test-content'
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/auroraconfigfile.json'))
  }
}