package stm

import stm.Program.*
import scala.concurrent.Await

@main def main(): Unit = {
  val tvar: TVar[Int] = TVar.makeUnsafe(5, "a")
  val transaction = for {
    v <- tvar.readTVar
    _ <- tvar.writeTVar(v + 1)
  } yield v

  given r: StmRuntime = stm.StmRuntime.default

  val prog = STM.atomic(transaction)
  val updatedValue = Await.result(prog, scala.concurrent.duration.Duration.Inf)
  println(s"Updated value: $updatedValue")

  val read = STM.atomic(tvar.readTVar)
  val newValue = Await.result(read, scala.concurrent.duration.Duration.Inf)

  println(s"New value: $newValue")

}

@main def main2(): Unit = {
  val tvarA: TVar[Int] = TVar.makeUnsafe(0, "a")
  val tvarB: TVar[Int] = TVar.makeUnsafe(10, "b")

  val move: STM[Unit] = for {
    a <- tvarA.readTVar
    _ <- tvarA.writeTVar(a - 1)
    b <- tvarB.readTVar
    _ <- tvarB.writeTVar(b + 1)
  } yield ()

  val moveReverse: STM[Unit] = for {
    b <- tvarB.readTVar
    _ <- tvarB.writeTVar(b - 1)
    a <- tvarA.readTVar
    _ <- tvarA.writeTVar(a + 1)
  } yield ()

  given r: StmRuntime = stm.StmRuntime.default

  // create 50 transactions and another 50 that reverse the previous ones
  val transactions = (1 to 50).map(_ => STM.atomic(move))
  val reverseTransactions = (1 to 50).map(_ => STM.atomic(moveReverse))

  Thread.sleep(5000)

  val readA = STM.atomic(tvarA.readTVar)
  val newValueA = Await.result(readA, scala.concurrent.duration.Duration.Inf)
  println(s"New value A: $newValueA")

  val readB = STM.atomic(tvarB.readTVar)
  val newValueB = Await.result(readB, scala.concurrent.duration.Duration.Inf)
  println(s"New value B: $newValueB")

  assert(newValueA == 0)
  assert(newValueB == 10)

}