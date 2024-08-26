package stm

import stm.Program.IO

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

// mutable storage cell
type IORef[A] = AtomicReference[A]

object IORef {
  // manipulated only through the following interface
  def newIORef[A](a: A): IO[IORef[A]] = Future.successful(new AtomicReference(a))
  def readIORef[A](ref: IORef[A]): IO[A] = Future.successful(ref.get())
  def writeIORef[A](ref: IORef[A], a: A): IO[Unit] = Future.successful(ref.set(a))
}


