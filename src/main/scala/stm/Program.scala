package stm

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

object Program {

  // I could've used something like IO from cats or ZIO but wanted to keep it simple
  // and not introduce any dependencies
  type IO[A] = Future[A]

  sealed trait TVar[A] {
    def readTVar: STM[A] = STM.Read(this)
    def writeTVar(a: A): STM[Unit] = STM.Write(this, a)

    def modify(f: A => A): STM[Unit] = for {
      a <- readTVar
      _ <- writeTVar(f(a))
    } yield ()

    def make(a: A): STM[TVar[A]] = TVar.newTVar(a)
    def makeUnsafe(a: A): TVar[A] = ???
  }

  object TVar {
    def newTVar[A](a: A): STM[TVar[A]] = STM.Pure(() => new TVar[A] {
      private val ref = new AtomicReference(a)
//      def get: A = ref.get()
//      def set(a: A): Unit = ref.set(a)
    })
  }

  // mutable storage cell
  type IORef[A] = AtomicReference[A]

  object IORef {
    // manipulated only through the following interface
    def newIORef[A](a: A): IO[IORef[A]] = Future.successful(new AtomicReference(a))
    def readIORef[A](ref: IORef[A]): IO[A] = Future.successful(ref.get())
    def writeIORef[A](ref: IORef[A], a: A): IO[Unit] = Future.successful(ref.set(a))
  }


  sealed trait STM[A] {
    def retry: STM[A] = STM.FlatMap(STM.Pure(() => ()), _ => STM.retry)
    def orElse(that: STM[A]): STM[A] = STM.FlatMap(this, _ => that)

    def flatMap[B](f: A => STM[B]): STM[B] = this match {
      case STM.FlatMap(stm, g) => STM.FlatMap(stm, g andThen (_.flatMap(f)))
      case x => STM.FlatMap(x, f)
    }
    def map[B](f: A => B): STM[B] = flatMap(a => STM.Pure(() => f(a)))
  }

  object STM {
    case class JournalEntry(tvar: TVar[?], value: Any)
    type Journal = List[JournalEntry]

    object Journal {
      val empty: List[JournalEntry] = List.empty
    }

    // Conceptually, aborts the transaction with no effect, and restarts it at the beginning.
    def retry[A]: STM[A] = ???

    case class Read[A](tvar: TVar[A]) extends STM[A]
    case class Write[A](tvar: TVar[A], value: A) extends STM[Unit]
    case class Pure[A](a: () => A) extends STM[A]

    case class Retry[A]() extends STM[A]

    case class FlatMap[A, B](stm: STM[A], f: A => STM[B]) extends STM[B]
    
    // Running the STM monad
    // stm should have a journal of all the operations that were performed
    // and when transitioning from STM to IO, we should validate the journal before commiting the changes

    
    // the journal should appear when evaluating the STM monad

    // when atomic is called, the STM checks that the logged accesses are valid
    // i.e no other transaction has modified the data since the transaction started

    // if the log is valid, the changes are committed to the heap
    def atomic[A](stm: STM[A]): IO[A] = {
      
      def go(stm: STM[A], journal: Journal): IO[A] = stm match {
        case Pure(a) => Future.successful(a())
        case Read(tvar) => tvar.readTVar.flatMap(a => go(a, journal ++ List(JournalEntry(tvar, a))))
        case Write(tvar, value) => IORef.writeIORef(tvar.asInstanceOf[IORef[A]], value.asInstanceOf[A]).map(_ => ())
        case FlatMap(stm, f) => go(stm, journal).flatMap(a => go(f(a), journal))
        case Retry() => Future.failed(new Exception("retry"))
      }

      go(stm, Journal.empty)
    }
    
  }


}
