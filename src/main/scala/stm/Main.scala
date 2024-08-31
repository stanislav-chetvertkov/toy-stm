package stm

import stm.Program.*
import scala.concurrent.Await

@main def main(): Unit = {
  val tvar: TVar[Int] = TVar.makeUnsafe(5, "a")
  val transaction = for {
    v <- STM.readTVar(tvar)
    _ <- tvar.writeTVar(v + 1)
  } yield v

  given r: StmRuntime = stm.StmRuntime.default

  val prog = STM.atomic(transaction)
  val updatedValue = Await.result(prog, scala.concurrent.duration.Duration.Inf)
  println(s"Updated value: $updatedValue")

  val read = STM.atomic(STM.readTVar(tvar))
  val newValue = Await.result(read, scala.concurrent.duration.Duration.Inf)

  println(s"New value: $newValue")

}

@main def main2(): Unit = {
  val tvarA: TVar[Int] = TVar.makeUnsafe(0, "a")
  val tvarB: TVar[Int] = TVar.makeUnsafe(10, "b")

  val move: STM[Unit] = for {
    a <- STM.readTVar(tvarA)
    _ <- tvarA.writeTVar(a - 1)
    b <- STM.readTVar(tvarB)
    _ <- tvarB.writeTVar(b + 1)
  } yield ()

  val moveReverse: STM[Unit] = for {
    b <- STM.readTVar(tvarB)
    _ <- tvarB.writeTVar(b - 1)
    a <- STM.readTVar(tvarA)
    _ <- tvarA.writeTVar(a + 1)
  } yield ()

  given r: StmRuntime = stm.StmRuntime.default

  STM.atomic(move)
  STM.atomic(moveReverse)
  STM.atomic(move)
  STM.atomic(moveReverse)
  STM.atomic(move)
  STM.atomic(moveReverse)

  Thread.sleep(5000)

  val readA = STM.atomic(STM.readTVar(tvarA))
  val newValueA = Await.result(readA, scala.concurrent.duration.Duration.Inf)
  println(s"New value A: $newValueA")

  val readB = STM.atomic(STM.readTVar(tvarB))
  val newValueB = Await.result(readB, scala.concurrent.duration.Duration.Inf)
  println(s"New value B: $newValueB")


}