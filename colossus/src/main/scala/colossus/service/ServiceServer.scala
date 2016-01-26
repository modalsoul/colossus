package colossus
package service

import core._
import controller._

import akka.event.Logging
import metrics._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import Codec._

/**
 * Configuration class for a Service Server Connection Handler
 * @param name the prefix to use for all metrics generated by the service
 * @param requestTimeout how long to wait until we timeout the request
 * @param requestBufferSize how many concurrent requests a single connection can be processing
 * @param logErrors if true, any uncaught exceptions or service-level errors will be logged
 * @param requestLogFormat if logErrors is enabled, this can be used to format the request which caused an error.  If not set, the toString function of the request is used
 */
case class ServiceConfig[I,O](
  name: MetricAddress,
  requestTimeout: Duration,
  requestBufferSize: Int = 100,
  logErrors: Boolean = true,
  requestLogFormat : Option[RequestFormatter[I]] = None,
  requestMetrics: Boolean = true
)

trait RequestFormatter[I] {
  def format(request : I) : String
}

class ServiceServerException(message: String) extends Exception(message)

class RequestBufferFullException extends ServiceServerException("Request Buffer full")

//if this exception is ever thrown it indicates a bug
class FatalServiceServerException(message: String) extends ServiceServerException(message)

class DroppedReply extends Error("Dropped Reply")


/**
 * The ServiceServer provides an interface and basic functionality to create a server that processes
 * requests and returns responses over a codec.
 *
 * A Codec is simply the format in which the data is represented.  Http, Redis protocol, Memcached protocl are all
 * examples(and natively supported).  It is entirely possible to use an additional Codec by creating a Codec to parse
 * the desired protocol.
 *
 * Requests can be processed synchronously or
 * asynchronously.  The server will ensure that all responses are written back
 * in the order that they are received.
 *
 */
abstract class ServiceServer[I,O]
  (codec: ServerCodec[I,O], config: ServiceConfig[I, O])(implicit io: IOSystem) 
extends Controller[I,O](codec, ControllerConfig(config.requestBufferSize, OutputController.DefaultDataBufferSize, Duration.Inf)) with ServerConnectionHandler {
  import ServiceServer._
  import WorkerCommand._
  import config._

  val log = Logging(io.actorSystem, name.toString())
  def tagDecorator: TagDecorator[I,O] = TagDecorator.default[I,O]

  implicit val col = io.metrics.base
  val requests  = col.getOrAdd(new Rate(name / "requests"))
  val latency   = col.getOrAdd(new Histogram(name / "latency", sampleRate = 0.25))
  val errors    = col.getOrAdd(new Rate(name / "errors"))
  val requestsPerConnection = col.getOrAdd(new Histogram(name / "requests_per_connection", sampleRate = 0.5, percentiles = List(0.5, 0.75, 0.99)))
  val concurrentRequests = col.getOrAdd(new Counter(name / "concurrent_requests"))

  //set to true when graceful disconnect has been triggered
  private var disconnecting = false

  //this is set to true when the head of the request queue is ready to write its
  //response but the last time we checked the output buffer it was full
  private var dequeuePaused = false

  def addError(err: Throwable, extraTags: TagMap = TagMap.Empty) {
    val tags = extraTags + ("type" -> err.getClass.getName.replaceAll("[^\\w]", ""))
    errors.hit(tags = tags)
  }

  case class SyncPromise(request: I) {
    val creationTime = System.currentTimeMillis

    def isTimedOut(time: Long) = !isComplete && requestTimeout.isFinite && (time - creationTime) > requestTimeout.toMillis

    private var _response: Option[O] = None
    def isComplete = _response.isDefined
    def response = _response.getOrElse(throw new Exception("Attempt to use incomplete response"))

    def complete(response: O) {
      _response = Some(response)
      checkBuffer()
    }

  }

  private val requestBuffer = new java.util.LinkedList[SyncPromise]()
  def currentRequestBufferSize = requestBuffer.size
  private var numRequests = 0

  override def idleCheck(period: Duration) {
    super.idleCheck(period)

    val time = System.currentTimeMillis
    while (requestBuffer.size > 0 && requestBuffer.peek.isTimedOut(time)) {
      //notice - completing the response will call checkBuffer which will write the error immediately
      requestBuffer.peek.complete(handleFailure(requestBuffer.peek.request, new TimeoutError))

    }
  }
    
  /**
   * Pushes the completed responses down to the controller so they can be returned to the client.
   */
  private def checkBuffer() {
    while (isConnected && requestBuffer.size > 0 && requestBuffer.peek.isComplete && canPush) {
      val done = requestBuffer.remove()
      val comp = done.response
      concurrentRequests.decrement()
      pushResponse(done.request, comp, done.creationTime) 
    }
    if (!canPush) {
      //this means the output buffer cannot accept any more messages, so we have
      //to pause dequeuing responses and wait for the next message in the output
      //buffer to be written
      dequeuePaused = true
    }
    checkGracefulDisconnect()
  }

  override def connectionClosed(cause : DisconnectCause) {
    super.connectionClosed(cause)
    requestsPerConnection.add(numRequests)
    concurrentRequests.decrement(num = requestBuffer.size)
  }

  override def connectionLost(cause : DisconnectError) {
    connectionClosed(cause)
  }

  protected def pushResponse(request: I, response: O, startTime: Long) {
    if (requestMetrics) {
      val tags = tagDecorator.tagsFor(request, response)
      requests.hit(tags = tags)
      latency.add(tags = tags, value = (System.currentTimeMillis - startTime).toInt)
    }
    val pushed = push(response, startTime) {
      case OutputResult.Success => {
        if (dequeuePaused) {
          dequeuePaused = false
          checkBuffer()
        }
      }
      case err => println(s"dropped reply: $err")
    }

    //this should never happen because we are always checking if the outputqueue
    //is full before calling this
    if (!pushed) {
      throw new FatalServiceServerException("Attempted to push response to a full output buffer")
    }
  }

  protected def processMessage(request: I) {
    numRequests += 1
    val startTime = System.currentTimeMillis
    /**
     * Notice, if the request buffer is full we're still adding to it, but by skipping
     * processing of requests we can hope to alleviate overloading
     */
    val response: Callback[O] = if (requestBuffer.size < requestBufferSize) {
      try {
        processRequest(request) 
      } catch {
        case t: Throwable => {
          Callback.successful(handleFailure(request, t))
        }
      }
    } else {
      Callback.successful(handleFailure(request, new RequestBufferFullException))
    }
    response match {
      case ConstantCallback(v) if (requestBuffer.size == 0 && canPush) => {
        //a constant callback means the result was produced immmediately, so if
        //request buffer is empty and we know we can write the result
        //immediately, we can totally skip the request buffering process
        val done = v match {
          case Success(yay) => yay
          case Failure(err) => handleFailure(request, err)
        }
        pushResponse(request, done, startTime)
        checkGracefulDisconnect()
      }
      case other => {
        val promise = new SyncPromise(request)
        requestBuffer.add(promise)
        concurrentRequests.increment()
        other.execute{
          case Success(res) => promise.complete(res)
          case Failure(err) => promise.complete(handleFailure(promise.request, err))
        }

      }
    }

  }

  private def handleFailure(request: I, reason: Throwable): O = {
    addError(reason)
    if (logErrors) {
      val formattedRequest = requestLogFormat.fold(request.toString)(_.format(request))
      log.error(reason, s"Error processing request: $formattedRequest: $reason")
    }
    processFailure(request, reason)
  }

  /**
   * Terminate the connection, but allow any outstanding requests to complete
   * (or timeout) before disconnecting
   */
  override def gracefulDisconnect() {
    pauseReads()
    disconnecting = true
    //notice - checkGracefulDisconnect must NOT be called here, since this is called in the middle of processing a request, it would end up
    //disconnecting before we have a change to finish processing and write the response

  }

  private def checkGracefulDisconnect() {
    if (disconnecting && requestBuffer.size == 0) {
      super.gracefulDisconnect()
    }
  }

  override def shutdownRequest() {
    gracefulDisconnect()
  }

  // ABSTRACT MEMBERS

  protected def processRequest(request: I): Callback[O]

  //DO NOT CALL THIS METHOD INTERNALLY, use handleFailure!!
  protected def processFailure(request: I, reason: Throwable): O

}

object ServiceServer {
  class TimeoutError extends Error("Request Timed out")

}
