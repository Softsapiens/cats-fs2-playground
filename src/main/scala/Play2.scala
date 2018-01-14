import cats._, cats.data._, cats.implicits._
import cats.effect._
import fs2._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Play2 extends App {
  implicit val computationPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  // This code hungs, due to callback
  object HungMerge {
    val hung = Stream.eval(IO.async[Int](_ => ()))
    val progress = Stream.constant(1, 10).covary[IO]
    (hung merge progress).compile.drain.unsafeRunSync()
  }

  object ProgressMerge {
    val progress = Stream.constant(1, 10).covary[IO].evalMap(e => IO {println(s"$e"); e})
    (progress merge progress).compile.drain.unsafeRunSync()
  }

  // Uncomment to run
  // ProgressMerge

  val program = for {
    q <- async.boundedQueue[IO, Int](10)

    deq = for {
      sd <- Stream.eval(q.dequeue1)
    } yield(sd)

    enq = for {
      e <- Stream.eval(q.enqueue1(666)).drain
    } yield (e)

    r <- enq.merge(deq).compile.toVector
  } yield (r)

  println(program.unsafeRunSync)

  println("Done")
}
