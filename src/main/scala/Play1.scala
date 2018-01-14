import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import StreamHelpers._

object Play1 extends App {
  implicit val computationPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  def run[F[_]](program: () => Stream[F, Unit], retry: FiniteDuration = 5.seconds)(
    implicit F: Effect[F], ec: ExecutionContext): IO[Unit] = {

    def loop[F[_]: Effect](s: Stream[F, Unit], retry: FiniteDuration, t: Int)(
      implicit ec: ExecutionContext): Stream[F, Unit] = {

      val F = implicitly[Sync[F]]
      val log = (m: String) => F.delay(println(m))

      s.handleErrorWith { err =>
        val scheduledProgram = Scheduler[F](2).flatMap(_.sleep[F](retry)).flatMap(_ => s)
        if( t>0 ) for {
          _ <- Stream.eval(log(err.getMessage))
          _ <- Stream.eval(log(s"Restarting in $retry..."))
          p <- loop[F](scheduledProgram, retry, t-1)
        } yield p
        else Stream.empty  // That not stops execution, only retries.
      }
    }

    F.runAsync(loop(program(), retry, 2).compile.drain) {
      case Right(_) => IO.unit
      case Left(e)  => IO.raiseError(e)
    }
  }



  val endOfWorld = for {
    _ <- IO { println(s"${Thread.currentThread().getName} starting...") }
    _ <- run(() => new Program[IO]().program)
    _ <- IO { println(s"${Thread.currentThread().getName} ending") }
  } yield()

  endOfWorld.unsafeRunSync

  scala.io.StdIn.readLine()
  System.exit(1)
}

object StreamHelpers {

  trait StreamEval[F[_]] {

    def evalF[A](body: => A): Stream[F, A]

    def liftSink[A](f: A => F[Unit]): Sink[F, A]

    def liftPipe[A, B](f: A => F[B]): Pipe[F, A, B]

  }

  implicit def syncStreamEvalInstance[F[_]](implicit F: Sync[F]): StreamEval[F] =
    new StreamEval[F] {
      override def evalF[A](body: => A): Stream[F, A] =
        Stream.eval[F, A](F.delay(body))

      override def liftSink[A](f: A => F[Unit]): Sink[F, A] =
        liftPipe[A, Unit](f)

      override def liftPipe[A, B](f: A => F[B]): Pipe[F, A, B] =
        _.evalMap(f)
  }
}

class Program[F[_]: Effect](implicit EC: ExecutionContext, SE: StreamEval[F]) {

  val program: Stream[F, Unit] =
    SE.evalF(println(s"${Thread.currentThread().getName} running program...")) ++ (throw new Exception("!@#$"))

}
