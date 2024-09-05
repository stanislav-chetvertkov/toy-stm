package stm

import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.internal.Alias
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

object Program {

  // I could've used something like IO from cats or ZIO but wanted to keep it simple
  // and not introduce any dependencies
  type IO[A] = Future[A]

  sealed trait TVar[A] {
    def hash(): Int

    def alias: String

    def writeTVar(a: A): STM[Unit] = STM.WriteToTVar(this, a)

    def readTVar: STM[A] = STM.ReadTVar(this)

    // internal method called only by the STM runtime
    def writeUnsafe(a: A): Unit

    def readUnsafe: A

  }

  object TVar {

    def makeUnsafe[A](a: A, aliasV: String): TVar[A] = new TVar[A] {
      private val ref = new AtomicReference(a)

      override def writeUnsafe(a: A): Unit = ref.set(a)

      override def readUnsafe: A = ref.get()

      override def hash(): Int = ref.hashCode()

      override def alias: String = aliasV
    }

    def newTVar[A](a: A, aliasV: String): STM[TVar[A]] = STM.Succeed(() => new TVar[A] {
      private val ref = new AtomicReference(a)

      override def writeUnsafe(a: A): Unit = ref.setRelease(a)

      override def readUnsafe: A = ref.get()

      override def hash(): Int = ref.hashCode()

      override def alias: String = aliasV
    })
  }

  type TLog = List[TLog.TLogEntry]

  // thread-local transaction log contains the reads and tentative writes to TVars
  // TLog should keep track of all the reads and writes and the corresponding values
  // if during the commit the values have changed, the transaction should be retried with the new values
  object TLog {
    sealed trait TLogEntry

    case class ReadTVarEntry(tvar: TVar[?], value: Any) extends TLogEntry

    case class WriteToTVarEntry[A](tvar: TVar[A], current: A, tentativeUpdate: A) extends TLogEntry

    val empty: TLog = List.empty

    // run by only one thread at a time
    def validate(log: TLog): Option[Map[TVar[?], Any]] = {
      val entries = log

      // a temporary map of tvar to value
      // read all the distinct TVars from the log and place them in a map
      var tvarToValue: Map[TVar[?], Any] = entries.collect {
        case ReadTVarEntry(tvar, value) => tvar
        case WriteToTVarEntry(tvar, _, value) => tvar
      }.distinct.map(tvar => tvar -> tvar.readUnsafe).toMap

      val result = entries.forall {
        case TLog.ReadTVarEntry(tvar, value) =>
          println(s"isValid read: ${tvar.alias}, observed: $value, actual: ${tvar.readUnsafe}")
          tvarToValue.get(tvar) match
            case Some(v) => v == value
            case None => false

        case TLog.WriteToTVarEntry(tvar, current, pendingUpdate) =>

          println(s"isValid write: ${tvar.alias}, observed: $current, pending: $pendingUpdate")
          // update the value in the map
          tvarToValue.get(tvar) match
            case Some(v) =>
              if current == v then
                tvarToValue = tvarToValue.updated(tvar, pendingUpdate)
                true
              else
                false
            case None =>
              false
      }

      if result then Some(tvarToValue) else None
    }

  }

  enum STMResult[A] {
    case Success(value: A, TLog: TLog)
    case Retry(errorDetails: Option[String])
  }


  sealed trait STM[A] {
    def orElse(that: STM[A]): STM[A] = STM.OrElse(this, that)

    def flatMap[B](f: A => STM[B]): STM[B] =
      STM.FlatMap(this, f)

    def map[B](f: A => B): STM[B] = flatMap(a => STM.Succeed(() => f(a)))

    def evaluate(): STMResult[A]

  }

  object STM {

    def retry[A](): Retry[A] = STM.Retry()

    case class ReadTVar[A](tvar: TVar[A]) extends STM[A] {
      override def evaluate(): STMResult[A] = {
        val readUnsafe = tvar.readUnsafe
        println(s"ReadTVar: ${tvar.alias}, observed:$readUnsafe")
        STMResult.Success(readUnsafe, TLog.ReadTVarEntry(tvar, readUnsafe) :: Nil)
      }
    }

    case class WriteToTVar[A](tvar: TVar[A], value: A) extends STM[Unit] {
      override def evaluate(): STMResult[Unit] = {
        val unsafe = tvar.readUnsafe
        println("WriteToTVar:" + tvar.alias + ", observed:" + unsafe + ", pending:" + value)
        STMResult.Success((), TLog.WriteToTVarEntry(tvar, unsafe, value) :: Nil)
      }
    }

    case class Succeed[A](a: () => A) extends STM[A] {
      override def evaluate(): STMResult[A] = {
        println("Succeed:" + a)
        STMResult.Success(a(), TLog.empty)
      }
    }

    case class Retry[A]() extends STM[A] {
      override def evaluate(): STMResult[A] = {
        STMResult.Retry(None)
      }
    }

    private case class OrElse[A](first: STM[A], second: STM[A]) extends STM[A] {
      override def evaluate(): STMResult[A] = {
        first.evaluate() match
          case STMResult.Success(value, log) => STMResult.Success(value, log)
          case STMResult.Retry(_) => second.evaluate()
      }
    }

    private case class FlatMap[A1, A2](first: STM[A1], f: A1 => STM[A2]) extends STM[A2] {
      override def evaluate(): STMResult[A2] = {
        first.evaluate() match
          case STMResult.Success(a1, aTLog) =>
            f(a1).evaluate() match
              case STMResult.Success(a2, bTLog) =>
                STMResult.Success(a2, aTLog ++ bTLog)
              case STMResult.Retry(errorDetails) => STMResult.Retry(errorDetails)

          case STMResult.Retry(errorDetails) => STMResult.Retry(errorDetails)
      }
    }

    // when atomic is called, the STM checks that the logged accesses are valid
    // i.e no other transaction has modified the data since the transaction started

    // if the log is valid, the changes are committed to the heap
    def atomic[A](stm: STM[A])(using runtime: StmRuntime): IO[A] = {

      // optimistic execution
      def evaluate(stm: STM[A]): Future[(A, TLog)] =
        Future(stm.evaluate()).flatMap {
          case STMResult.Success(value, log) => Future.successful((value, log))
          case STMResult.Retry(errorDetails) =>
            println(s"Retrying transaction for ${stm.hashCode()}")
            evaluate(stm)
        }

      for {
        (value, log) <- evaluate(stm)
        _ = println("Atomic commit log:" + log)
        _ = println("Atomic commit value:" + value)
        promise = Promise[TLogCommitResult]()
        _ = runtime.publish(log, promise)
        result <- promise.future
        _ = println("Atomic commit result:" + result)
        result <- result match
          case TLogCommitResult.Committed =>
            println("Transaction committed")
            Future.successful(value)
          case TLogCommitResult.Rejected =>
            // retry the transaction
            println("Transaction rejected, retrying")
            atomic(stm)
      } yield result
    }

  }

  enum TLogCommitResult {
    case Committed
    case Rejected
  }

  case class TLogWithCallback(tlog: TLog, callback: Promise[TLogCommitResult])

}
