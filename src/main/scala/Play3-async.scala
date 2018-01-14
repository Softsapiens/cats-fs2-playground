import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2.async._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play3 extends App {
  val executor = Executors.newFixedThreadPool(2)
  implicit val computationPool = ExecutionContext.fromExecutor(executor)

  def program: IO[Unit] = for {
    message <- Promise.empty[IO, String]
    done <- Promise.empty[IO, Unit]
    _ <- fork { done.get.flatMap( _ => IO { println(s"${Thread.currentThread().getName} job done"); executor.shutdownNow() })}
    reader <- fork {
      for {
        _ <- IO { println(s"${Thread.currentThread().getName} Reader") }
        v <- message.get
        _ <- IO { println(s"readed value $v") }
        _ <- done.complete(())
      } yield ()
    }
    writer <- fork {
      for {
        _ <- IO{ println(s"${Thread.currentThread().getName} Writer") }
        v = "Hi concurrent world"
        _ <- IO { println(s"sleeping a bit..."); Thread.sleep(3000); println(s"writing value $v") }
        _ <- message.complete(v)
      } yield ()
    }
    _ <- fork { IO {println(s"${Thread.currentThread().getName} This is a dummy fork!")} }
  } yield ()

  program.unsafeRunSync
}
