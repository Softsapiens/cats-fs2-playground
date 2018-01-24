trait SafeApp {
  import cats.effect.IO

  def main(args: List[String]): IO[Unit]

  def main(args: Array[String]): Unit = main(args.toList).unsafeRunSync()
}
