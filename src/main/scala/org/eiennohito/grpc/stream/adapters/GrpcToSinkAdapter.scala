package org.eiennohito.grpc.stream.adapters

import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import akka.stream.{Attributes, Inlet, SinkShape}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.{Status, StatusRuntimeException}
import io.grpc.stub.StreamObserver

import scala.concurrent.{Future, Promise}

/**
  * @author eiennohito
  * @since 2016/04/28
  */
trait ReadyHandler {
  def onReady(): Unit
  def onCancel(): Unit
}

trait ReadyInput {
  def isReady: Boolean
}

class GrpcToSinkAdapter[T](data: StreamObserver[T], rdy: ReadyInput)
  extends GraphStageWithMaterializedValue[SinkShape[T], Future[ReadyHandler]] with StrictLogging {

  private val in = Inlet[T]("Grpc.Out")
  override val shape = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {

    val hndler =  Promise[ReadyHandler]

    val logic = new GraphStageLogic(shape) { logic =>

      @volatile private var keepGoing = true

      override def preStart() = {
        super.preStart()

        hndler.success(new ReadyHandler {
          private val readyCall = getAsyncCallback { _: Unit => signalReady() }
          private val cancelCall = getAsyncCallback { _: Unit => signalCancel() }
          override def onReady() = readyCall.invoke(())
          override def onCancel() = {
            keepGoing = false
            cancelCall.invoke(())
          }
        })

        if (rdy.isReady) {
          pull(in)
        }
      }

      private def signalReady(): Unit = {
        if (!hasBeenPulled(in)) {
          pull(in)
        }
      }

      private def signalCancel(): Unit = {
        cancel(in)
        logic.completeStage()
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (!keepGoing) {
             return
          }

          data.onNext(grab(in))
          if (rdy.isReady) {
            pull(in)
          }
        }

        override def onUpstreamFinish() = try {
          data.onCompleted()
        } catch {
          case e: StatusRuntimeException =>
            logger.debug("grpc status exception", e)
        }

        override def onUpstreamFailure(ex: Throwable) = {
          logger.error("signalling error to grpc", ex)
          data.onError(Status.INTERNAL.withCause(ex).asException())
        }
      })
    }

    (logic, hndler.future)
  }
}
