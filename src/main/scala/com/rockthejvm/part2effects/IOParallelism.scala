package com.rockthejvm.part2effects

import cats.syntax.apply.*
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.implicits.*
import com.rockthejvm.utils.*
import cats.Parallel

object IOParallelism extends IOApp.Simple {

  // IOs are usually sequential
  val anisIO   = IO(s"[${Thread.currentThread().getName}] Ani")
  val kamranIO = IO(s"[${Thread.currentThread().getName}] Kamran")

  val composedIO = for
    ani    <- anisIO
    kamran <- kamranIO
  yield s"$ani and $kamran love Rock the JVM"

  val meaningOfLife: IO[Int] = IO.delay(42)
  val favLang: IO[String]    = IO.delay("Scala")
  val goalInLife = (meaningOfLife.debug, favLang.debug).mapN((num, string) =>
    s"my goal in life is $num and $string"
  )

  // parallelism on IOs
  // convert a sequential IO to parallel IO
  val parIO1: IO.Par[Int]    = Parallel[IO].parallel(meaningOfLife.debug)
  val parIO2: IO.Par[String] = Parallel[IO].parallel(favLang.debug)
  val goalInLifeParallel = (parIO1, parIO2).mapN((num, string) =>
    s"my goal in life is $num and $string"
  )

  // turn back to sequential
  val goalInLife_v2: IO[String] = Parallel[IO].sequential(goalInLifeParallel)

  // shorthand:
  import cats.syntax.parallel.*
  val goalInLife_v3 =
    (meaningOfLife.debug, favLang.debug).parMapN((num, string) =>
      s"my goal in life is $num and $string"
    )

  // regarding failure:
  val aFailure: IO[String] =
    IO.raiseError(new RuntimeException("I can't do this!"))
  // compose success + failure
  val parallelWithFailure =
    (meaningOfLife.debug, aFailure.debug).parMapN((i, s) => s"$i$s")

  // compose failure + failure
  val failureTwo: IO[String] =
    IO.raiseError(new RuntimeException("Second failure!"))
  val twoFailures: IO[String] = (aFailure.debug, failureTwo).parMapN(_ + _)
  val twoFailuresDelayed: IO[String] =(IO(Thread.sleep(1000)) >> aFailure, failureTwo).parMapN(_ + _)

  override def run: IO[Unit] =
    twoFailuresDelayed.debug.void
}
