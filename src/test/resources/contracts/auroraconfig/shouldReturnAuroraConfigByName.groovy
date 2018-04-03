import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/auroraconfig/aurora-config-name'
  }
  response {
    status 200
    body(file('auroraconfig-response.json'))
    headers {
      contentType(applicationJson())
    }
  }
}