import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/auroraconfig/aurora-config-name/filenames'
  }
  response {
    status 200
    body(file('filenames-response.json'))
    headers {
      contentType(applicationJson())
    }
  }
}