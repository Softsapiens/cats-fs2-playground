import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2.async._
import fs2.async.mutable.Queue
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play4 extends App {

  def master[F[_]](l: String, q: Queue[F, Cmd], join: Promise[F, Unit], fa: F[String])(implicit F: Effect[F]): F[Unit] =
    for {
      s <- fa
      cmd = if ( s.equalsIgnoreCase("stop") ) Stop
      else if ( s.equalsIgnoreCase("err") ) Err(new Exception("forced error"))
      else Msg(s)
      _ <- delay { println(s"${Thread.currentThread().getName} $l enqueuing ${cmd}") }
      _ <- q.enqueue1(cmd)
      _ <- cmd match {
        case Stop => join.complete(())
        case _ => master(l, q, join, fa)
      }
    } yield ()

  def job[F[_]](l: String, c: Cmd)(implicit F: Effect[F]): F[Unit] = c match {
    case Msg(s) => delay {
      Thread.sleep(1000); println(s"${Thread.currentThread().getName} $l has processed ${c} at ${(System.currentTimeMillis/100).toString.substring(8)}")
    }
    case Err(e) => for {
      _ <- delay { println(s"${Thread.currentThread().getName} $l throws exception ${c} at ${(System.currentTimeMillis/100).toString.substring(8)}") }
      _ <- F.raiseError[Unit](e)
      _ <- delay { println("after exception") }
    } yield ()
    case _ => F.pure(())
  }

  def worker[F[_]](l: String, q: Queue[F, Cmd],  join: Promise[F, Unit])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] =
    for {
      c <- q.dequeue1
      _ <- delay { println(s"${Thread.currentThread().getName} $l dequeuing ${c}") }
      _ <- c match {
        case Stop => join.complete(())
        case _ => for {
          _ <- fork { job(s"job-${(System.currentTimeMillis/100).toString.substring(8)}", c) }
          _ <- worker(l, q, join)
        } yield ()
      }
    } yield ()

  def joiner[F[_], A](ps: List[Promise[F, A]])(implicit F: Effect[F]) =
  for {
    _ <- ps.traverse_(_.get)
    _ <- delay { println(s"${Thread.currentThread().getName} job done"); executor.shutdownNow() }
  } yield ()

  sealed trait Cmd
  final case object Stop extends Cmd
  final case class Msg(s: String) extends Cmd
  final case class Err(e: Throwable) extends Cmd

  def delay[F[_]: Effect, A](a: => A)(implicit F: Effect[F]): F[A] =
    F.suspend(F.pure(a))

  def program[F[_]](implicit F: Effect[F], ec: ExecutionContext): F[Unit] = for {
    q <- boundedQueue[F, Cmd](10)
    join1 <- Promise.empty[F, Unit]
    join2 <- Promise.empty[F, Unit]
    join3 <- Promise.empty[F, Unit]

    _ <- fork {
      master("master-StdIn", q, join1, delay { scala.io.StdIn.readLine })
    }
    _ <- fork {
      master("master-Time1", q, join3, delay { Thread.sleep(2000); (System.currentTimeMillis/100).toInt.toString.substring(8) })
    }
    _ <- fork {
      master("master-Time2", q, join3, delay { Thread.sleep(4000); (System.currentTimeMillis/100).toInt.toString.substring(8) })
    }
    _ <- fork {
      worker("worker1", q, join2)
    }
    _ <- fork {
      joiner(List(join1, join2))
    }
  } yield ()

  val executor = Executors.newFixedThreadPool(4)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)
  program[IO].unsafeRunSync
}
