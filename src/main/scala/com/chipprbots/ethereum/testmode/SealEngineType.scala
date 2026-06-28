package com.chipprbots.ethereum.testmode

enum SealEngineType:
  // Do not check `nonce` and `mixhash` field in blockHeaders
  case NoProof
  // Do not check `nonce` and `mixhash` field in blockHeaders + Do not check mining reward (block + uncle headers)
  case NoReward
