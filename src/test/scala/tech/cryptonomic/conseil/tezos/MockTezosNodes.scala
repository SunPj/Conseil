package tech.cryptonomic.conseil.tezos
import tech.cryptonomic.conseil.util.JsonUtil._

import scala.concurrent.Future
import scala.util.Try

/**
  * Defines mocking scenarios for testing against tezos nodes
  *
  * The following diagrams outlines all testing scenarios available
  *
  * In each scenario we can imagine a "snapshot" of the node and the results
  * that it is expected to return from the block request, based on the
  * exact time-frame (Tn) and branch considered the "main" one at that time
  *
  * Most snapshot for the same time-frame will return the same results. It
  * doesn't needs to be so, but it simplifies the data definition
  *
  * SCENARIO 1: no fork
  *
  * -----[T1]---------------  branch-0
  *
  *
  * SCENARIO 2: single fork
  *
  *             -----------[T3]----------  branch-1
  * -----[T1]--/---[T2]------------------  branch-0
  *
  * SCENARIO 3: singel fork alternating with the original
  *
  *             -----------[T3]---------[T5]----  branch-1
  * -----[T1]--/---[T2]------------[T4]---------  branch-0
  *
  *
  * SCENARIO 4: two forks alternating with the original
  *
  *             -------------------[T4]---------------  branch-2
  *             -----------[T3]----------[T5]---------  branch-1
  * -----[T1]--/---[T2]-------------------------[T6]--  branch-0
  *
  */
object MockTezosNodes {

  //endpoint to retrieves the head block
  private val headRequestUrl = "blocks/head"
  //endpoint matcher to retrieve a specific block offset, extracts the hash and the offset value
  private val HashEndpointMatch = """blocks/([A-Za-z0-9]+)~(\d+)""".r
  //endpoint matcher for operation requests, no need to extract
  private val OperationsEndpointMatch = """blocks/([A-Za-z0-9]+)/operations""".r

  private val emptyBlockOperationsResult = Future.successful("[[]]")

  /* expected hash based on branch and time-frame
   * this must match the base file hash returned from the mock node when the head block is requested
   */
  def getHeadHash(onBranch: Int, atTime: Int) = (onBranch, atTime) match {
    case (_, 1) => "BKy8NcuerruFgeCGAoUG3RfjhHf1diYjrgD2qAJ5rNwp2nRJ9H4"
    case (_, 2) => "BKpFANTnUBqVe8Hm4rUkNuYtJkg7PjLrHjkQaNQj7ph5Bi6qXVi"
    case (_, 3) => "BLFaY9jHrkuxnQAQv3wJif6V7S6ekGHoxbCBmeuFyLixGAYm3Bp"
    case (0, 4) => "BMSw4mdnPTRpNtNoRBGpnYDGGH9pcxB2FHBxQeonyUunaV5bAz1"
    case (2, 4) => "BMKRY5YvFhbwcLPsV3vfvYZ97ktSfu2eJTx2V21PfUxUEYXzTsp"
    case (_, 5) => "BM7bFA88UaPfBEW2XPZGCaB1rH38tjx21J571JxvkFp3JvSuBpr"
    case (_, 6) => "BMeiBtFrXuVN7kcVaC4mt1dbncX2n8tb76qUeM4JCr97Cb7U84u"
    case _ => throw new IllegalArgumentException(s"no scenario defined to get a head hash for branch-$onBranch at time T$atTime")
  }

  /* only a limited number of offsets are expected on the mock calls,
   * based on the scenario and current time-frame
   */
  def getMaxOffsets(onBranch: Int, atTime: Int) = (onBranch, atTime) match {
    case (_, 1) => 2
    case (_, 2) => 1
    case (_, 3) => 1
    case (0, 4) => 1
    case (2, 4) => 2
    case (_, 5) => 1
    case (_, 6) => 3
    case _ => throw new IllegalArgumentException(s"no scenario defines expected offsets for brach-$onBranch at time T$atTime")

  }

  /** currently defines batched-get in terms of async-get only */
  trait BaseMock extends TezosRPCInterface {

    import scala.concurrent.ExecutionContext.Implicits.global

    def runBatchedGetQuery(network: String, commands: List[String], concurrencyLevel: Int): Future[List[String]] =
      Future.traverse(commands)(runAsyncGetQuery(network, _))

    def runGetQuery(network: String, command: String): Try[String] = ???

    def runAsyncGetQuery(network: String, command: String): Future[String] = ???

    def runPostQuery(network: String, command: String, payload: Option[JsonString] = None): Try[String] = ???

    def runAsyncPostQuery(network: String, command: String, payload: Option[JsonString] = None): Future[String] = ???

  }

  /**
    * create a simulated node interface to return pre-canned responses
    * following a known scenario
    */
  def getNode(onBranch: Int, atTime: Int) = new BaseMock {
    import scala.concurrent.ExecutionContext.Implicits.global

    //the head should depend on the branch and time both
    val headHash = getHeadHash(onBranch, atTime)

    //the number of offsets to load should never be higher than this
    val maxExpectedOffset = getMaxOffsets(onBranch, atTime)

    //use some mapping to define valid offsets per scenario?
    def isValidOffset(offset: String) = Try {
      val intOffset = offset.toInt
      intOffset >= 0 && intOffset <= maxExpectedOffset
    }.getOrElse(false)

    //will build the results based on local files by matching request params
    override def runAsyncGetQuery(network: String, command: String): Future[String] =
      command match {
        case `headRequestUrl` =>
          //will return the block at offset 0
          getStoredBlock(0, onBranch, atTime)
        case HashEndpointMatch(`headHash`, offset) if isValidOffset(offset) =>
          getStoredBlock(offset.toInt, onBranch, atTime)
        case HashEndpointMatch(`headHash`, offset) =>
          throw new IllegalStateException(s"The node simulated for branch-$onBranch at time T$atTime received an unexpected block offset request in $command")
        case OperationsEndpointMatch(hash) =>
          emptyBlockOperationsResult
        case _ =>
          throw new IllegalStateException(s"Unexpected request path in $command")
      }

    /**
      * Helper function that returns the json block data stored in the forking_tests files.
      * @param offset how many levels away from the current block head
      * @param branch which test chain branch we're working off of
      * @param time which iteration of lorre we're working off of
      * @return a full json string with the block information
      */
    private def getStoredBlock(offset: Int, branch: Int, time: Int): Future[String] =
      Future(scala.io.Source.fromFile(s"src/test/resources/forking_tests/branch-$branch/time-T$time/head~$offset.json").mkString)

  }

  private type TezosNode = TezosRPCInterface

  /**
    * Allows to advance forth and back in integer steps from `0` to the provided `max`
    * Instances are not thread-safe
    * @param size the cursor will always be bound to this value
    */
  class Frame(max: Int) {

    private[MockTezosNodes] val cursor = {
      val synced = new scala.concurrent.SyncVar[Int]()
      synced.put(0)
      synced
    }

    /**
      * Increment the frame cursor without overflowing
      * @return `true` if the cursor actually changed
      */
    def next(): Boolean = {
      val frame = cursor.take()
      cursor.put(scala.math.max(frame + 1, max)) //no overflow
      cursor.get != frame
    }

    /**
      * Decrement the frame cursor without underflowing
      * @return `true` if the cursor actually changed
      */
    def prev(): Boolean = {
      val frame = cursor.take()
      cursor.put(scala.math.min(frame - 1, 0)) //no underflow
      cursor.get != frame
    }
  }

  /**
    * Creates a sequence of mock nodes, used to simulate
    * diffent states of the remote node in time
    * The returned `Frame` is used to move "time" ahead and back by
    * pointing to different nodes in the sequence
    */
  def sequenceNodes(first: TezosNode, rest: TezosNode*): (TezosNode, Frame) = {
    val nodes = new NodeSequence(first, rest: _*)
    (nodes, nodes.frame)
  }

  /*
   * A sequence of tezos interfaces that will delegate the get request
   * to the one currently selected by the internal `frame` variable
   */
  private class NodeSequence(first: TezosNode, rest: TezosNode*) extends BaseMock {

    private val nodes = first :: rest.toList

    val frame = new Frame(nodes.size - 1)

    override def runAsyncGetQuery(network: String, command: String): Future[String] =
      nodes(frame.cursor.get).runAsyncGetQuery(network, command)

  }

  //SCENARIO 1 on the scheme
  lazy val nonForkingScenario = getNode(onBranch = 0, atTime = 1)


  //SCENARIO 2 on the scheme
  lazy val singleForkScenario = sequenceNodes(
    getNode(onBranch = 0, atTime = 1),
    getNode(onBranch = 0, atTime = 2),
    getNode(onBranch = 1, atTime = 3)
  )

  //SCENARIO 3 on the scheme
  lazy val singleForkAlternatingScenario = sequenceNodes(
    getNode(onBranch = 0, atTime = 1),
    getNode(onBranch = 0, atTime = 2),
    getNode(onBranch = 1, atTime = 3),
    getNode(onBranch = 0, atTime = 4),
    getNode(onBranch = 1, atTime = 5)
  )

  //SCENARIO 4 on the scheme
  lazy val twoForksAlternatingScenario = sequenceNodes(
    getNode(onBranch = 0, atTime = 1),
    getNode(onBranch = 0, atTime = 2),
    getNode(onBranch = 1, atTime = 3),
    getNode(onBranch = 2, atTime = 4),
    getNode(onBranch = 1, atTime = 5),
    getNode(onBranch = 0, atTime = 6)
  )

}