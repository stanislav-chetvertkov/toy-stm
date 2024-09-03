package stm

import stm.Program.{TLog, TLogCommitResult, TLogWithCallback, TVar}
import stm.StmRuntime.startTLogProcessor

import java.util.concurrent.BlockingQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class StmRuntime {
  val tlogs: BlockingQueue[TLogWithCallback] = new java.util.concurrent.LinkedBlockingQueue[TLogWithCallback]()

  def publish(tlog: TLog, callback: Promise[TLogCommitResult]): Unit = {
    tlogs.put(TLogWithCallback(tlog, callback))
  }

  startTLogProcessor(tlogs) // do not allow others to call the start method, maybe guard it with a lock
}

object StmRuntime {

  lazy val default = new StmRuntime()

  def startTLogProcessor(tlogs: BlockingQueue[TLogWithCallback]): Unit = {
    // start a thread that processes the tlogs
    // if the tlog is valid, commit it and notify the callback
    // if the tlog is invalid, reject it and notify the callback

    val job = Future {
      println("Starting TLog processor")

      while (true) {
        val tlogWithCallback = tlogs.take()
        val tlog = tlogWithCallback.tlog
        val callback = tlogWithCallback.callback

        TLog.validate(tlog) match
          case Some(value) =>
            commit(value)
            callback.success(TLogCommitResult.Committed)
          case None =>
            callback.success(TLogCommitResult.Rejected)
      }
    }

    // Register callbacks
    job.onComplete {
      case scala.util.Success(value) => println(s"TLog processor completed with: $value")
      case scala.util.Failure(exception) => println(s"TLog processor failed with: ${exception.getMessage}")
    }

  }

  private def commit(output: Map[TVar[?], Any]) = {
    output.foreach {
      case (tvar, value) => tvar.writeUnsafe(value.asInstanceOf)
    }
  }

}