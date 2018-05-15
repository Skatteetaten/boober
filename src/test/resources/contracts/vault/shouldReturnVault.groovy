package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/v1\/vault\/[a-z]+\/[a-z]+/),
        test('/v1/vault/vaultcollection/vaultfile')
    )
    headers {
      contentType(applicationJson())
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/vaults.json'))
  }
}