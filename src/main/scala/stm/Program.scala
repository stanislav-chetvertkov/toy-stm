package stm

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Program {

  // I could've used something like IO from cats or ZIO but wanted to keep it simple
  // and not introduce any dependencies
  type IO[A] = Future[A]

  sealed trait TVar[A] {
    def hash(): Int

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

    def make(a: A): STM[TVar[A]] = TVar.newTVar(a)

    def makeUnsafe(a: A): TVar[A] = ???
  }

  object TVar {

    def makeUnsafe[A](a: A): TVar[A] = new TVar[A] {
      private val ref = new AtomicReference(a)

      override def writeUnsafe(a: A): Unit = ref.set(a)

      override def readUnsafe: A = ref.get()

      override def hash(): Int = ref.hashCode()
    }

    def newTVar[A](a: A): STM[TVar[A]] = STM.Succeed(() => new TVar[A] {
      private val ref = new AtomicReference(a)

      override def writeUnsafe(a: A): Unit = ref.set(a)

      override def readUnsafe: A = ref.get()

      override def hash(): Int = ref.hashCode()
    })
  }


  class TLog {
    private var log: List[TLog.TLogEntry] = List.empty

    def entries: List[TLog.TLogEntry] = log

    def append(entry: TLog.TLogEntry): TLog = {
      log = log :+ entry
      this
    }

    def append(another: TLog): TLog = {
      log = log ++ another.entries
      this
    }

    def getLog: List[TLog.TLogEntry] = log
  }

  // thread-local transaction log contains the reads and tentative writes to TVars
  object TLog {
    sealed trait TLogEntry

    case class ReadTVarEntry(tvar: TVar[?], value: Any) extends TLogEntry

    case class WriteToTVarEntry[A](tvar: TVar[A], value: A) extends TLogEntry

    val empty = new TLog
  }

  type STMResult[A] = (A, TLog)

  sealed trait STM[A] {
    //    def retry: STM[A] = STM.FlatMap(STM.Pure(() => ()), _ => STM.retry)
    //    def orElse(that: STM[A]): STM[A] = STM.FlatMap(this, _ => that)


    def flatMap[B](f: A => STM[B]): STM[B] =
      STM.FlatMap(this, f)

    def map[B](f: A => B): STM[B] = flatMap(a => STM.Succeed(() => f(a)))

    def run(): STMResult[A]
  }

  object STM {

    def readTVar[A](tVar: TVar[A]): STM[A] = {
      ReadTVar(tVar)
    }

    // Conceptually, aborts the transaction with no effect, and restarts it at the beginning.
    //    def retry[A]: STM[A] = ???

    case class ReadTVar[A](tvar: TVar[A]) extends STM[A] {
      override def run(): STMResult[A] = {
        println(s"ReadTVar: $tvar")
        val readUnsafe = tvar.readUnsafe
        println("read unsafe:" + readUnsafe)
        (readUnsafe, TLog.empty.append(TLog.ReadTVarEntry(tvar, readUnsafe)))
      }
    }

    case class WriteToTVar[A](tvar: TVar[A], value: A) extends STM[Unit] {
      override def run(): STMResult[Unit] = {
        println("WriteToTVar:" + value)
        ((), TLog.empty.append(TLog.WriteToTVarEntry(tvar, value)))
      }
    }

    case class Succeed[A](a: () => A) extends STM[A] {
      override def run(): STMResult[A] = {
        println("Succeed:" + a)
        (a(), TLog.empty)
      }
    }

    case class Retry[A]() extends STM[A] {
      //we don't want an empty log to run forever
      override def run(): STMResult[A] = throw new RuntimeException("retry")
    }

    case class FlatMap[A1, A2](first: STM[A1], f: A1 => STM[A2]) extends STM[A2] {
      override def run(): STMResult[A2] = {
        println("FlatMap:" + first)
        val (a1, log1) = first.run()
        val (a2, log2) = f(a1).run()
        (a2, log1.append(log2))
      }
    }

    // when atomic is called, the STM checks that the logged accesses are valid
    // i.e no other transaction has modified the data since the transaction started

    // if the log is valid, the changes are committed to the heap
    def atomic[A](stm: STM[A]): IO[Unit] = {
      val (value, log) = stm.run()
      println("Atomic commit log:" + log.entries)
      println("Atomic commit value:" + value)

      def isValid(log: TLog): Boolean = {
        val entries = log.getLog
        entries.forall {
          case TLog.ReadTVarEntry(tvar, value) => tvar.readUnsafe == value
          case TLog.WriteToTVarEntry(tvar, value) => tvar.readUnsafe == value
        }
      }

      def commit(log: TLog): Unit = {
        val entries = log.getLog
        entries.foreach {
          case TLog.ReadTVarEntry(tvar, value) => ()
          case TLog.WriteToTVarEntry(tvar, value) => tvar.writeUnsafe(value)
        }
      }

      //      if (isValid(log)) {
      //        Future(commit(log))
      //      } else {
      //        atomic(stm)
      //      }

      Future.successful(())

      //todo: use a hashmap for the log where key is the reference address of the tvar

    }


    // TLog should keep track of all the reads and writes and the corresponding values
    // if during the commit the values have changed, the transaction should be retried with the new values

  }


}
