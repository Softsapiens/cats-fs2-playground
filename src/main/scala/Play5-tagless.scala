import cats._
import cats.data._
import cats.implicits._
import cats.effect._
import fs2.async._
import fs2.async.mutable.Queue
import java.util.concurrent.{Executors, ScheduledExecutorService}

import fs2.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Play5 extends SafeApp {
  import Cmd._
  import CmdResult._

  def master[F[_]](l: String, q: Queue[F, Cmd], join: Promise[F, Unit], fa: F[String])(implicit F: Effect[F]): F[Unit] =
    for {
      s <- fa
      cmd = if ( s.equalsIgnoreCase("stop") ) CStop
      else if ( s.equalsIgnoreCase("err") ) CErr(new Exception("forced error"))
      else CMsg(s)
      _ <- F.delay { println(s"${Thread.currentThread().getName} $l enqueuing ${cmd}") }
      _ <- q.enqueue1(cmd)
      _ <- cmd match {
        case CStop => join.complete(())
        case _ => master(l, q, join, fa)
      }
    } yield ()

  def job[F[_]](l: String, c: Cmd, q: Queue[F, CmdResult])(implicit F: Effect[F]): F[Unit] = c match {
    case CMsg(s) => for {
      res <- F.delay {
        Thread.sleep(1000)
        val v = RSuccess(s"${Thread.currentThread().getName} $l has processed ${c} at ${(System.currentTimeMillis / 100).toInt.toString.substring(6)}")
        println(v)
        v
      }
      _ <- q.enqueue1(res)
    } yield ()
    case CErr(e) => for {
      _ <- F.delay {
        println(s"${Thread.currentThread().getName} $l throws exception ${c} at ${(System.currentTimeMillis / 100).toInt.toString.substring(6)}")
      }
      _ <- F.raiseError[Unit](e)
      _ <- q.enqueue1(RErr(e))
    } yield ()
    case _ => F.pure(())
  }

  def worker[F[_]](l: String, qIn: Queue[F, Cmd], qOut: Queue[F, CmdResult], join: Promise[F, Unit])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] =
    for {
      c <- qIn.dequeue1
      _ <- F.delay { println(s"${Thread.currentThread().getName} $l dequeuing ${c}") }
      _ <- c match {
        case CStop => for {
          _ <- F.delay {
            println(s"${Thread.currentThread().getName} $l stopping at ${(System.currentTimeMillis / 100).toInt.toString.substring(6)}")
          }
          _ <- join.complete(())
        }yield ()
        case _ => for {
          _ <- fork { job(s"job-${(System.currentTimeMillis/100).toString.substring(8)}", c, qOut) }
          _ <- worker(l, qIn, qOut, join)
        } yield ()
      }
    } yield ()

  def joiner[F[_], A](ps: List[Promise[F, A]])(implicit F: Effect[F]) =
  for {
    _ <- ps.traverse_(_.get)
    _ <- F.delay {
      println(s"${Thread.currentThread().getName} job done")
    }
  } yield ()

  sealed trait Cmd
  object Cmd {

    final case object CStop extends Cmd
    final case class CMsg(s: String) extends Cmd
    final case class CErr(e: Throwable) extends Cmd

  }

  sealed trait CmdResult
  object CmdResult {

    final case class RSuccess[A](a: A) extends CmdResult
    final case class RErr[A](a: A) extends CmdResult

  }

  def program[F[_]](implicit F: Effect[F], StdIOF: StdInAlg[F], TimeIOF: TimeAlg[F], ec: ExecutionContext): F[Unit] = for {
    qIn <- boundedQueue[F, Cmd](10)
    qOut <- boundedQueue[F, CmdResult](10)
    join1 <- Promise.empty[F, Unit]
    join2 <- Promise.empty[F, Unit]
    join3 <- Promise.empty[F, Unit]

    _ <- fork {
      master("master-StdIn", qIn, join1, StdIOF.readLine)
    }
    _ <- fork {
      master("master-Time1", qIn, join3, TimeIOF.timeFrom(2))
    }
    _ <- fork {
      master("master-Time2", qIn, join3, TimeIOF.timeFrom(4))
    }
    _ <- fork {
      worker("worker1", qIn, qOut, join2)
    }
    _ <- joiner(List(join1, join2))
  } yield ()


  trait TimeAlg[F[_]] {
    def timeFrom(w: Long): F[String]
  }

  implicit def timeIO(implicit F: Effect[IO], scheduler: Scheduler) = new TimeAlg[IO] {
    def timeFrom(w: Long): IO[String] = for {
      _ <- scheduler.effect.sleep(FiniteDuration(w, "s"))
      r <- F.delay {
        (System.currentTimeMillis/100).toInt.toString.substring(6)
      }
    } yield (r)
  }

  trait StdInAlg[F[_]] {
    def readLine: F[String]
  }

  implicit object StdInIO extends StdInAlg[IO] {
    def readLine: IO[String] = implicitly[Effect[IO]].delay { scala.io.StdIn.readLine }
  }

  type TestF[A] = StateT[IO, List[String], A]

  implicit object StdInT extends StdInAlg[TestF] {
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

  implicit def timeT(implicit scheduler: Scheduler) = new TimeAlg[TestF] {
    def timeFrom(w: Long): TestF[String] = StateT { ls =>
      implicitly[Effect[IO]].delay {
        println(s"state=${ls}")
        Thread.sleep(w) // PENDING:  scheduler.effect.sleep[IO](100.millis) // for a non-blocking waits
        val t = (System.currentTimeMillis/100).toInt.toString.substring(8)

        (ls, t)
      }
    }
  }

  val executor = Executors.newFixedThreadPool(4)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)
  val executor2 = Executors.newScheduledThreadPool(2, Executors.defaultThreadFactory)
  implicit val scheduler = Scheduler.fromScheduledExecutorService(executor2)

  def main(args: List[String]): IO[Unit] = for {
    _ <- program[IO]
    _ <- IO {
      executor.shutdownNow
      executor2.shutdownNow
    }
  } yield ()
}
