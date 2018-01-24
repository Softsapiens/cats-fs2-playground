import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2.async._
import fs2.async.mutable.Queue
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play5 extends App {

  def master[F[_]](l: String, q: Queue[F, Cmd], join: Promise[F, Unit], fa: F[String])(implicit F: Effect[F]): F[Unit] =
    for {
      s <- fa
      cmd = if ( s.equalsIgnoreCase("stop") ) Stop
      else if ( s.equalsIgnoreCase("err") ) Err(new Exception("forced error"))
      else Msg(s)
      _ <- F.delay { println(s"${Thread.currentThread().getName} $l enqueuing ${cmd}") }
      _ <- q.enqueue1(cmd)
      _ <- cmd match {
        case Stop => join.complete(())
        case _ => master(l, q, join, fa)
      }
    } yield ()

  def job[F[_]](l: String, c: Cmd)(implicit F: Effect[F]): F[Unit] = c match {
    case Msg(s) => F.delay {
      Thread.sleep(1000); println(s"${Thread.currentThread().getName} $l has processed ${c} at ${(System.currentTimeMillis/100).toString.substring(8)}")
    }
    case Err(e) => for {
      _ <- F.delay { println(s"${Thread.currentThread().getName} $l throws exception ${c} at ${(System.currentTimeMillis/100).toString.substring(8)}") }
      _ <- F.raiseError[Unit](e)
      _ <- F.delay { println("after exception") }
    } yield ()
    case _ => F.pure(())
  }

  def worker[F[_]](l: String, q: Queue[F, Cmd],  join: Promise[F, Unit])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] =
    for {
      c <- q.dequeue1
      _ <- F.delay { println(s"${Thread.currentThread().getName} $l dequeuing ${c}") }
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
    _ <- F.delay { println(s"${Thread.currentThread().getName} job done"); executor.shutdownNow() }
  } yield ()

  sealed trait Cmd
  final case object Stop extends Cmd
  final case class Msg(s: String) extends Cmd
  final case class Err(e: Throwable) extends Cmd

  def program[F[_]](implicit F: Effect[F], StdIOF: StdInAlg[F], TimeIOF: TimeAlg[F], ec: ExecutionContext): F[Unit] = for {
    q <- boundedQueue[F, Cmd](10)
    join1 <- Promise.empty[F, Unit]
    join2 <- Promise.empty[F, Unit]
    join3 <- Promise.empty[F, Unit]

    _ <- fork {
      master("master-StdIn", q, join1, StdIOF.readLine)
    }
    _ <- fork {
      master("master-Time1", q, join3, TimeIOF.timeFrom(2000))
    }
    _ <- fork {
      master("master-Time2", q, join3, TimeIOF.timeFrom(4000))
    }
    _ <- fork {
      worker("worker1", q, join2)
    }
    _ <- fork {
      joiner(List(join1, join2))
    }
  } yield ()


  trait TimeAlg[F[_]] {
    def timeFrom(w: Long): F[String]
  }

  implicit object TimeIO extends TimeAlg[IO] {
    def timeFrom(w: Long): IO[String] = implicitly[Effect[IO]].delay {
      Thread.sleep(2000) // PENDING:  scheduler.effect.sleep[IO](100.millis) // for a non-blocking waits
      (System.currentTimeMillis/100).toInt.toString.substring(8)
    }
  }

  trait StdInAlg[F[_]] {
    def readLine: F[String]
  }

  implicit object StdInIO extends StdInAlg[IO] {
    def readLine: IO[String] = implicitly[Effect[IO]].delay { scala.io.StdIn.readLine }
  }

  val executor = Executors.newFixedThreadPool(4)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)

  sys.addShutdownHook { executor.shutdownNow }

  type TestF[A] = StateT[IO, List[String], A]

  implicit object StdInTl extends StdInAlg[TestF] {
    def readLine: TestF[String] = StateT {
      case x :: xs =>
        println(s"value= ${x}")
        implicitly[Effect[IO]].pure {
          (xs, x)
        }
      // PENDING: what happens when input list is empty?
      case Nil => println(s"NIL"); implicitly[Effect[IO]].raiseError(new Exception("Empty input!"))
    }
  }

  implicit object TimeTl extends TimeAlg[TestF] {
    def timeFrom(w: Long): TestF[String] = StateT { ls =>
      implicitly[Effect[IO]].delay {
        println(s"state=${ls}")
        Thread.sleep(w) // PENDING:  scheduler.effect.sleep[IO](100.millis) // for a non-blocking waits
        val t = (System.currentTimeMillis/100).toInt.toString.substring(8)

        (ls, t)
      }
    }
  }

  program[IO].unsafeRunSync
}
