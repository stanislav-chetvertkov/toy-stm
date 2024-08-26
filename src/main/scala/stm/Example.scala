package stm


//object Example extends App {

//  type Resource = TVar[Int]
//
//  def putR(r: Resource, n: Int): STM[Unit] = for
//    v <- r.readTVar
//    _ <- r.writeTVar(v + n)
//  yield ()
//
//  def getR(r: Resource, i: Int): STM[Unit] = for
//    v <- r.readTVar
//    _ <- if v < i then STM.retry else r.writeTVar(v - i)
//  yield v
//}
