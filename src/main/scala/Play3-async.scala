import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2.async._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play3 extends App {

  def publisher(message: Promise[IO, String]): IO[Unit] = {
    for {
      _ <- IO{ println(s"${Thread.currentThread().getName} Publisher") }
      v = "Hi concurrent world"
      millis = 3000
      _ <- IO { println(s"sleeping ${millis/1000} seconds..."); Thread.sleep(millis); println(s"writing value $v") }
      _ <- message.complete(v)
      _ <- IO { println(s"${Thread.currentThread().getName} Published value $v") }
    } yield ()
  }

  def suscribers(n: Int, message: Promise[IO, String]): IO[Unit] =
    if ( n == 0 ) IO.unit
    else fork {
      for {
        _ <- IO { println(s"${Thread.currentThread().getName} Subscriber $n") }
        v <- message.get
        _ <- IO { println(s">>> Subscriber $n get value $v") }
      } yield ()
    } flatMap { _ => suscribers(n-1, message) }

  def dummy(l: String, n: Int, join: Promise[IO, Unit]): IO[Unit] =
    for {
      s <- IO { scala.io.StdIn.readLine }
      _ <- IO { println(s"${Thread.currentThread().getName} $l stdin: ${s} -- ${n-1} keys left") }
      _ <- if ( n>1 ) dummy(l, n-1, join) else join.complete(())
    } yield ()

  def joiner[A](ps: List[Promise[IO, A]]) =
  for {
    _ <- ps.traverse_(_.get)
    _ <- IO { println(s"${Thread.currentThread().getName} job done"); executor.shutdownNow() }
  } yield ()

  def program: IO[Unit] = for {
    message <- Promise.empty[IO, String]
    join1 <- Promise.empty[IO, Unit]
    join2 <- Promise.empty[IO, Unit]

    _ <- fork {
      dummy("dummy1", 2, join1)
    }
    _ <- fork {
      dummy("dummy2", 2, join2)
    }
    _ <- fork {
      joiner(List(join1, join2))
    }
    rs <- suscribers(10, message)
    w <- fork {
      publisher(message)
    }
  } yield ()

  val executor = Executors.newFixedThreadPool(3)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)
  program.unsafeRunSync
}
