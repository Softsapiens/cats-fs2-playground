import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2.async._
import fs2.async.mutable.Queue
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play4 extends App {

  def master(l: String, q: Queue[IO, Cmd], join: Promise[IO, Unit]): IO[Unit] =
    for {
      s <- IO { scala.io.StdIn.readLine }
      cmd = if ( s.equalsIgnoreCase("stop") ) Stop else Msg(s)
      _ <- IO { println(s"${Thread.currentThread().getName} $l enqueuing ${cmd}") }
      _ <- q.enqueue1(cmd)
      _ <- cmd match {
        case Stop => join.complete(())
        case _ => master(l, q, join)
      }
    } yield ()

  def worker(l: String, q: Queue[IO, Cmd],  join: Promise[IO, Unit]): IO[Unit] =
    for {
      c <- q.dequeue1
      _ <- IO { println(s"${Thread.currentThread().getName} $l dequeuing ${c}") }
      _ <- c match {
        case Stop => join.complete(())
        case _ => worker(l, q, join)
      }
    } yield ()

  def joiner[A](ps: List[Promise[IO, A]]) =
  for {
    _ <- ps.traverse_(_.get)
    _ <- IO { println(s"${Thread.currentThread().getName} job done"); executor.shutdownNow() }
  } yield ()

  sealed trait Cmd
  final case object Stop extends Cmd
  final case class Msg(s: String) extends Cmd

  def program: IO[Unit] = for {
    q <- boundedQueue[IO, Cmd](1000)
    join1 <- Promise.empty[IO, Unit]
    join2 <- Promise.empty[IO, Unit]

    _ <- fork {
      master("master1", q, join1)
    }
    _ <- fork {
      worker("worker1", q, join2)
    }
    _ <- fork {
      joiner(List(join1, join2))
    }
  } yield ()

  val executor = Executors.newFixedThreadPool(3)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)
  program.unsafeRunSync
}
