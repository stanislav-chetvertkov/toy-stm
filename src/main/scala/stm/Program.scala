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

    //    def readTVar: STM[A] = {
    //      STM.FlatMap(STM.ReadTVar(this), a => STM.Succeed(() => a))
    //    }
    def writeTVar(a: A): STM[Unit] = STM.WriteToTVar(this, a)

    def writeUnsafe(a: A): Unit

    def readUnsafe: A

    //    def modify(f: A => A): STM[Unit] = for {
    //      a <- readTVar
    //      _ <- writeTVar(f(a))
    //    } yield ()

    def make(a: A, alias: String): STM[TVar[A]] = TVar.newTVar(a, alias)

    def makeUnsafe(a: A): TVar[A] = ???
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

  type STMResult[A] = (A, TLog)

  sealed trait STM[A] {
    //    def retry: STM[A] = STM.FlatMap(STM.Pure(() => ()), _ => STM.retry)
    //    def orElse(that: STM[A]): STM[A] = STM.FlatMap(this, _ => that)


    def flatMap[B](f: A => STM[B]): STM[B] =
      STM.FlatMap(this, f)

    def map[B](f: A => B): STM[B] = flatMap(a => STM.Succeed(() => f(a)))

    def evaluateTentativeUpdates(): STMResult[A]
  }

  object STM {

    def readTVar[A](tVar: TVar[A]): STM[A] = {
      ReadTVar(tVar)
    }

    // todo: implement, aborts the transaction with no effect, and restarts it at the beginning.
    //    def retry[A]: STM[A] = ???

    case class ReadTVar[A](tvar: TVar[A]) extends STM[A] {
      override def evaluateTentativeUpdates(): STMResult[A] = {
        val readUnsafe = tvar.readUnsafe
        println(s"ReadTVar: ${tvar.alias}, observed:$readUnsafe")
        (readUnsafe, TLog.ReadTVarEntry(tvar, readUnsafe) :: Nil)
      }
    }

    case class WriteToTVar[A](tvar: TVar[A], value: A) extends STM[Unit] {
      override def evaluateTentativeUpdates(): STMResult[Unit] = {
        val unsafe = tvar.readUnsafe
        println("WriteToTVar:" + tvar.alias + ", observed:" + unsafe + ", pending:" + value)
        ((), TLog.WriteToTVarEntry(tvar, unsafe, value) :: Nil)
      }
    }

    case class Succeed[A](a: () => A) extends STM[A] {
      override def evaluateTentativeUpdates(): STMResult[A] = {
        println("Succeed:" + a)
        (a(), TLog.empty)
      }
    }

    case class Retry[A]() extends STM[A] {
      //we don't want an empty log to run forever
      override def evaluateTentativeUpdates(): STMResult[A] = throw new RuntimeException("retry")
    }

    case class FlatMap[A1, A2](first: STM[A1], f: A1 => STM[A2]) extends STM[A2] {
      override def evaluateTentativeUpdates(): STMResult[A2] = {
        val (a1, log1) = first.evaluateTentativeUpdates()
        val (a2, log2) = f(a1).evaluateTentativeUpdates()
        (a2, log1 ++ log2)
      }
    }

    // when atomic is called, the STM checks that the logged accesses are valid
    // i.e no other transaction has modified the data since the transaction started

    // if the log is valid, the changes are committed to the heap
    def atomic[A](stm: STM[A])(using runtime: StmRuntime): IO[A] = {
      for {
        (value, log) <- Future(stm.evaluateTentativeUpdates())
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

  //  def commit(log: TLog): Unit = {
  //    val entries = log
  //    entries.foreach {
  //      case TLog.ReadTVarEntry(tvar, value) => ()
  //      case TLog.WriteToTVarEntry(tvar, previous, newValue) => tvar.writeUnsafe(newValue)
  //    }
  //  }

  def commit(output: Map[TVar[?], Any]) = {
    output.foreach {
      case (tvar, value) => tvar.writeUnsafe(value.asInstanceOf)
    }
  }


}
