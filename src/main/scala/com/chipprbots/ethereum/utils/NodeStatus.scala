package com.chipprbots.ethereum.utils

import java.net.InetSocketAddress

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import com.chipprbots.ethereum.network.*

enum ServerStatus:
  case NotListening
  case Listening(address: InetSocketAddress)

case class NodeStatus(key: AsymmetricCipherKeyPair, serverStatus: ServerStatus, discoveryStatus: ServerStatus):

  val nodeId: Array[Byte] =
    key.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId // interop: BC API returns AsymmetricKeyParameter
