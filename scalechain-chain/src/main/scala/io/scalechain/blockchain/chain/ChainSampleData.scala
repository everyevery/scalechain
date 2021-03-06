package io.scalechain.blockchain.chain

import io.scalechain.blockchain.proto._
import io.scalechain.blockchain.script.HashCalculator
import io.scalechain.blockchain.storage.BlockIndex
import io.scalechain.blockchain.transaction._
import io.scalechain.util.HexUtil

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class TestBlockIndex extends BlockIndex {
  var bestBlockHash : BlockHash = null
  var bestBlockHeight = -1
  val transactions = new mutable.HashMap[TransactionHash, Transaction]
  val blocks = new mutable.HashMap[BlockHash, (BlockInfo,Block)]

  def addBlock(block : Block, height : Int) : Unit = {
    val blockHash : BlockHash = BlockHash( HashCalculator.blockHeaderHash(block.header) )
    blocks.put(blockHash, (
      BlockInfo(
        height,
        block.transactions.length,
        0,
        block.header,
        None
      ),
      block)
    )
    if (bestBlockHeight < height) {
      bestBlockHash = blockHash
      bestBlockHeight = height
    }
  }

  /** Get a block by its hash.
    *
    * @param blockHash
    */
  def getBlock(blockHash : BlockHash) : Option[(BlockInfo, Block)] = {
    blocks.get(blockHash)
  }

  def addTransaction(transactionHash : TransactionHash, transaction : Transaction) : Unit = {
    transactions.put(transactionHash, transaction)
  }

  /** Get a transaction by its hash.
    *
    * @param transactionHash
    */
  def getTransaction(transactionHash : TransactionHash) : Option[Transaction] = {
    transactions.get(transactionHash)
  }
}

/** A transaction output with an outpoint of it.
  *
  * @param output The transaction output.
  * @param outPoint The transaction out point which is used for pointing the transaction output.
  */
case class OutputWithOutPoint(output : TransactionOutput, outPoint : OutPoint)


case class TransactionWithName(name:String, transaction:Transaction)

/**
  * A blockchain sample data for testing purpose only.
  */
class ChainSampleData(chainEventListener: Option[ChainEventListener]) extends TransactionTestDataTrait{

  private val blockIndex = new TestBlockIndex()

  val availableOutputs = new TransactionOutputSet()

  def generateAddress(account:String) : AddressData = {
    val addressData = generateAddress()
    onAddressGeneration(account, addressData.address)
    addressData
  }

  def onAddressGeneration(account:String, address : CoinAddress) : Unit = {
    // by default, do nothing.
  }

  object Alice {
    val Addr1 = generateAddress("Alice") // for receiving from others
    val Addr2 = generateAddress("Alice") // for receiving changes
  }

  object Bob {
    val Addr1 = generateAddress("Bob") // for receiving from others
    val Addr2 = generateAddress("Bob") // for receiving changes
  }

  object Carry {
    val Addr1 = generateAddress("Carry") // for receiving from others
    val Addr2 = generateAddress("Carry") // for receiving changes
  }


  object TestBlockchainView extends BlockchainView {
    def getTransactionOutput(outPoint : OutPoint) : TransactionOutput = {
      availableOutputs.getTransactionOutput(outPoint)
    }
    def getIterator(height : Long) : Iterator[ChainBlock] = {
      // unused.
      assert(false)
      null
    }
    def getBestBlockHeight() : Long = {
      blockIndex.bestBlockHeight
    }

    def getTransaction(transactionHash : Hash) : Option[Transaction] = {
      blockIndex.getTransaction(TransactionHash(transactionHash.value))
    }
  }

  def getTxHash(transactionWithName : TransactionWithName) = Hash( HashCalculator.transactionHash(transactionWithName.transaction))
  def getBlockHash(block : Block) = Hash( HashCalculator.blockHeaderHash(block.header) )

  /** Add all outputs in a transaction into an output set.
    *
    * @param outputSet The output set where each output of the given transaction is added.
    * @param transactionWithName The transaction that has outputs to be added to the set.
    */
  def addTransaction(outputSet : TransactionOutputSet, transactionWithName : TransactionWithName ) = {
    val transactionHash = getTxHash(transactionWithName)
    var outputIndex = -1

    transactionWithName.transaction.outputs foreach { output =>
      outputIndex += 1
      outputSet.addTransactionOutput( OutPoint(transactionHash, outputIndex), output )
    }

    blockIndex.addTransaction(TransactionHash(transactionHash.value), transactionWithName.transaction)
    chainEventListener.map(_.onNewTransaction(transactionWithName.transaction))
    //println(s"transaction(${transactionWithName.name}) added : ${transactionHash}")
  }

  /** Create a generation transaction
    *
    * @param amount The amount of coins to generate
    * @param generatedBy The OutputOwnership that owns the newly generated coin. Ex> a coin address.
    * @return The newly generated transaction
    */
  def generationTransaction( name : String,
                             amount : CoinAmount,
                             generatedBy : OutputOwnership
                           ) : TransactionWithName = {
    val transaction = TransactionBuilder.newBuilder(availableOutputs)
      .addGenerationInput(CoinbaseData("The scalable crypto-current, ScaleChain by Kwanho, Chanwoo, Kangmo."))
      .addOutput(CoinAmount(50), generatedBy)
      .build()
    val transactionWithName = TransactionWithName(name, transaction)
    addTransaction( availableOutputs, transactionWithName)
    transactionWithName
  }


  /** Get an output of a given transaction.
    *
    * @param transactionWithName The transaction where we get an output.
    * @param outputIndex The index of the output. Zero-based index.
    * @return The transaction output with an out point.
    */
  def getOutput(transactionWithName : TransactionWithName, outputIndex : Int) : OutputWithOutPoint = {
    val transactionHash = Hash(HashCalculator.transactionHash(transactionWithName.transaction))
    OutputWithOutPoint( transactionWithName.transaction.outputs(outputIndex), OutPoint(transactionHash, outputIndex))
  }

  case class NewOutput(amount : CoinAmount, outputOwnership : OutputOwnership)

  /** Create a new normal transaction
    *
    * @param spendingOutputs The list of spending outputs. These are spent by inputs.
    * @param newOutputs The list of newly created outputs.
    * @return
    */
  def normalTransaction( name : String, spendingOutputs : List[OutputWithOutPoint], newOutputs : List[NewOutput]) : TransactionWithName = {
    val builder = TransactionBuilder.newBuilder(availableOutputs)

    spendingOutputs foreach { output =>
      builder.addInput(output.outPoint)
    }

    newOutputs foreach { output =>
      builder.addOutput(output.amount, output.outputOwnership)
    }

    val transaction = builder.build()

    val transactionWithName = TransactionWithName(name, transaction)

    addTransaction( availableOutputs, transactionWithName)

    transactionWithName
  }

  def addBlock(block: Block) : Unit = {
    val blockHeight = blockIndex.bestBlockHeight+1
    blockIndex.addBlock(block, blockHeight)
    chainEventListener.map(_.onNewBlock(
      ChainBlock(blockHeight, block)
    ))
  }

  def newBlock(transactionsWithName : List[TransactionWithName]) : Block = {
    val builder = BlockBuilder.newBuilder()

    transactionsWithName.map(_.transaction) foreach { transaction =>
      builder.addTransaction(transaction)
    }

    val block = builder.build(blockIndex.bestBlockHash, System.currentTimeMillis() / 1000)
    addBlock(block)
    block
  }

  // Test cases may override this method to check the status of blockchain.
  def onStepFinish(stepNumber : Int): Unit = {
    // to nothing
  }


  // Put genesis block.
  addBlock(env.GenesisBlock)

  /////////////////////////////////////////////////////////////////////////////////
  // Step 1
  /////////////////////////////////////////////////////////////////////////////////
  // Block height  : 1
  // Confirmations : 3
  /////////////////////////////////////////////////////////////////////////////////
  // Scenario : Coin Generation Only
  // Alice mines a coin with amount 50 SC.
  val S1_AliceGenTx = generationTransaction( "S1_AliceGenTx", CoinAmount(50), Alice.Addr1.address )
  val S1_AliceGenTxHash = getTxHash(S1_AliceGenTx)
  val S1_AliceGenCoin_A50 = getOutput(S1_AliceGenTx, 0)
  assert(S1_AliceGenCoin_A50.outPoint.outputIndex == 0)

  // Create the first block.
  val S1_Block = newBlock(List(S1_AliceGenTx))
  val S1_BlockHash = getBlockHash(S1_Block)
  val S1_BlockHeight = 1
  onStepFinish(1)

  /////////////////////////////////////////////////////////////////////////////////
  // Step 2
  /////////////////////////////////////////////////////////////////////////////////
  // Block height  : 2
  // Confirmations : 2
  /////////////////////////////////////////////////////////////////////////////////
  val S2_BobGenTx = generationTransaction( "S2_BobGenTx", CoinAmount(50), Bob.Addr1.address )
  val S2_BobGenTxHash = getTxHash(S2_BobGenTx)
  val S2_BobGenCoin_A50 = getOutput(S2_BobGenTx, 0)
  /////////////////////////////////////////////////////////////////////////////////
  // Scenario : Send to one address keeping change
  // Alice sends 10 SC to Bob, and keeps 39 SC paying 1 SC as fee.
  val S2_AliceToBobTx = normalTransaction(
    "S2_AliceToBobTx",
    spendingOutputs = List(S1_AliceGenCoin_A50),
    newOutputs = List(
      NewOutput(CoinAmount(10), Bob.Addr1.address),
      NewOutput(CoinAmount(39), Alice.Addr2.address)
      // We have very expensive fee, 1 SC ㅋㅋㅋㅋㅋㅋㅋㅋㅋ
    )
  )
  val S2_AliceToBobTxHash = getTxHash(S2_AliceToBobTx)

  val S2_BobCoin1_A10         = getOutput(S2_AliceToBobTx, 0)
  val S2_AliceChangeCoin1_A39 = getOutput(S2_AliceToBobTx, 1)

  // Create the second block.
  val S2_Block = newBlock(List(S2_BobGenTx, S2_AliceToBobTx))
  val S2_BlockHash = getBlockHash(S2_Block)
  val S2_BlockHeight = 2

  onStepFinish(2)

  /////////////////////////////////////////////////////////////////////////////////
  // Step 3
  /////////////////////////////////////////////////////////////////////////////////
  // Block height  : 3
  // Confirmations : 1
  /////////////////////////////////////////////////////////////////////////////////
  val S3_CarryGenTx = generationTransaction( "S3_CarryGenTx", CoinAmount(50), Carry.Addr1.address )
  val S3_CarryGenTxHash = getTxHash(S3_CarryGenTx)
  val S3_CarrayGenCoin_A50 = getOutput(S3_CarryGenTx, 0)
  /////////////////////////////////////////////////////////////////////////////////
  // Scenario : Spending a coin, send to many addresses
  // Step 3 : Bob sends 2 SC to Alice, 3 SC to Carray, and keeps 5 SC paying no fee.
  val S3_BobToAliceAndCarrayTx = normalTransaction(
    "S3_BobToAliceAndCarrayTx",
    spendingOutputs = List(S2_BobCoin1_A10),
    newOutputs = List(
      NewOutput(CoinAmount(2), Alice.Addr1.address),
      NewOutput(CoinAmount(3), Carry.Addr1.address),
      NewOutput(CoinAmount(5), Bob.Addr2.address)
    )
  )
  val S3_BobToAliceAndCarrayTxHash = getTxHash(S3_BobToAliceAndCarrayTx)

  val S3_AliceCoin1_A2     = getOutput(S3_BobToAliceAndCarrayTx, 0)
  // The Carray.Addr1 has ownership.
  val S3_CarrayCoin1_A3    = getOutput(S3_BobToAliceAndCarrayTx, 1)
  val S3_BobChangeCoin1_A5 = getOutput(S3_BobToAliceAndCarrayTx, 2)

  // Create the second block.
  val S3_Block = newBlock(List(S3_CarryGenTx, S3_BobToAliceAndCarrayTx))
  val S3_BlockHash = getBlockHash(S3_Block)
  val S3_BlockHeight = 3

  onStepFinish(3)

  /////////////////////////////////////////////////////////////////////////////////
  // Step 4
  /////////////////////////////////////////////////////////////////////////////////
  // Scenario : Spending a coin send to one address.
  // Alice sends 2 SC to Carray paying 1 SC as fee.
  val S4_AliceToCarryTx = normalTransaction(
    "S4_AliceToCarryTx",
    spendingOutputs = List(S3_AliceCoin1_A2),
    newOutputs = List(
      NewOutput(CoinAmount(2), Carry.Addr2.address)
    )
  )
  val S4_AliceToCarryTxHash = getTxHash(S4_AliceToCarryTx)
  // The Carray.Addr2 has ownership.
  val S4_CarryCoin2_A1     = getOutput(S4_AliceToCarryTx, 0)
  onStepFinish(4)

  /////////////////////////////////////////////////////////////////////////////////
  // Step 5
  /////////////////////////////////////////////////////////////////////////////////
  // Scenaro : Merge coins into one coin for an address
  // Carry uses two coins 3 SC and 1 SC to send 4SC to Alice without any fee.
  val S5_CarryMergeToAliceTx = normalTransaction(
    "S5_CarryMergeToAliceTx",
    spendingOutputs = List(S3_CarrayCoin1_A3, S4_CarryCoin2_A1),
    newOutputs = List(
      NewOutput(CoinAmount(4), Alice.Addr1.address)
    )
  )
  val S5_CarryMergeToAliceTxHash = getTxHash(S5_CarryMergeToAliceTx)
  val S5_AliceCoin3_A4     = getOutput(S5_CarryMergeToAliceTx, 0)
  onStepFinish(5)

}
