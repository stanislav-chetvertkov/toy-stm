package stm

import stm.Program.*

@main def main(): Unit = {
  val tvar: TVar[Int] = TVar.makeUnsafe(5)
  val prog = for {
    v <- STM.readTVar(tvar)
    _ <- tvar.writeTVar(v + 1)
  } yield v

  println(prog)

  STM.atomic(prog)

//  println(tvar.readUnsafe)
}