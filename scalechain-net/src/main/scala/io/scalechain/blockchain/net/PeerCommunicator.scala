package io.scalechain.blockchain.net

import java.net.{InetAddress, InetSocketAddress}

import io.scalechain.blockchain.proto._
import io.scalechain.blockchain.script.HashCalculator
import io.scalechain.util.{HexUtil, StringUtil}
import org.slf4j.LoggerFactory

/**
  * Created by kangmo on 5/22/16.
  */
class PeerCommunicator(peerSet : PeerSet) {
  private val logger = LoggerFactory.getLogger(classOf[PeerCommunicator])

  /*
    protected[net] def sendToAny(message : ProtocolMessage): Unit = {
    }
    protected[net] def sendTo(remoteAddress : InetSocketAddress, message : ProtocolMessage): Unit = {
    }
  */

  protected[net] def sendToAll(message : ProtocolMessage): Unit = {
    peerSet.peers() foreach { case (address: InetSocketAddress, peer : Peer) =>
      val messageString = message match {
        case m : Block => {
          s"Block. Hash : ${HexUtil.hex(HashCalculator.blockHeaderHash(m.header))}"
        }
        case m : Transaction => {
          s"Transaction. Hash : ${HexUtil.hex(HashCalculator.transactionHash(m))}"
        }
        case m => {
          StringUtil.getBrief(m.toString, 256)
        }
      }
      logger.info(s"Sending to one of all peers : ${address}, ${messageString}")
      peer.send(message)
    }
  }

  /** Propagate a newly mined block to the peers. Called by a miner, whenever a new block was mined.
    *
    * @param block The newly mined block to propagate.
    */
  def propagateBlock(block : Block) : Unit = {
    // Propagating a block is an urgent job to do. Without broadcasting the inventories, send the block itself to the network.
    sendToAll(block)
  }

  /** Propagate a newly received transaction to the peers.
    *
    * @param transaction The transaction to propagate.
    */
  def propagateTransaction(transaction : Transaction) : Unit = {
    sendToAll(transaction)
  }

  /** Get the list of information on each peer.
    *
    * Used by : getpeerinfo RPC.
    *
    * @return The list of peer information.
    */
  def getPeerInfos() : List[PeerInfo] = {

    var peerIndex = 0;

    val peerInfosIter = for (
      (address, peer) <- peerSet.peers()
    ) yield {
      peerIndex += 1
      PeerInfo.create(peerIndex, address, peer)
    }

    peerInfosIter.toList
  }
}
