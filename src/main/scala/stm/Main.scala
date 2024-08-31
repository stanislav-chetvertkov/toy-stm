package stm

import stm.Program.*
import scala.concurrent.Await

@main def main(): Unit = {
  val tvar: TVar[Int] = TVar.makeUnsafe(5)
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