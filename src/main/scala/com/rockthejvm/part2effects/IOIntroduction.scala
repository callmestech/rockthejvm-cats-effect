package com.rockthejvm.part2effects

import cats.effect.IO
import scala.io.StdIn

object IOIntroduction {

  // IO
  val ourFirstIO: IO[Int] = IO.pure(42) // arg should not have side effects
  val aDelayedIO: IO[Int] = IO.delay({
    println("I'm producing an integer")
    54
  })

  val aDelayedIO_v2: IO[Int] = IO {
    println("I'm producing an integer")
    54
  }

  // map, flatMap
  val improvedMeaningOfLife = ourFirstIO.map(_ * 2)
  val printedMeaningOfLife  = ourFirstIO.flatMap(mol => IO.delay(println(mol)))

  def smallProgram(): IO[Unit] = for
    line1 <- IO(StdIn.readLine())
    line2 <- IO(StdIn.readLine())
    _     <- IO.delay(println(line1 + line2))
  yield ()

  // mapN - combine IO effects as tuples
  import cats.syntax.apply._
  val combinedMeaningOfLife = (ourFirstIO, improvedMeaningOfLife).mapN(_ + _)

  def smallProgram_v2(): IO[Unit] =
    (IO(StdIn.readLine()), IO(StdIn.readLine())).mapN(_ + _).map(println)

  /*
   * Exercises
   */

  // 1 - sequence two IOs and take the result of the LAST one
  def sequenceTakeLast[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa.flatMap(_ => iob)

  // 2 - sequence two IOs and take the result of the FIRST one
  def sequenceTakeFirst[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    for
      a <- ioa
      _ <- iob
    yield a

  // 3 - repeat an IO effect forever
  def forever[A](io: IO[A]): IO[A] =
    io.flatMap(_ => forever(io))

  // 4 - convert an IO to a different type
  def convert[A, B](ioa: IO[A], value: B): IO[B] =
    ioa.map(_ => value)

  // 5 - discard value inside an IO, just return Unit
  def asUnit[A](ioa: IO[A]): IO[Unit] =
    ioa.map(_ => ())

  // 6 - fix stack recursion
  def sum(n: Int): Int =
    if n <= 0 then 0
    else n + sum(n - 1)

  def sumIO(n: Int): IO[BigInt] =
    if n <= 0 then IO(BigInt(n))
    else
      for
        x   <- IO(BigInt(n))
        sum <- sumIO(n - 1)
      yield x + sum

  val cache: scala.collection.mutable.Map[Int, BigInt] =
    scala.collection.mutable.Map()

  def fromCacheOrCompute(n: Int) =
    cache
      .get(n)
      .fold {
        fibonacci(n)
          .flatTap(computed => IO(cache.put(n, computed)))
      }(IO(_))

  def fibonacci(n: Int): IO[BigInt] =
    n match
      case 1 => IO(BigInt(0))
      case 2 => IO(BigInt(1))
      case _ =>
        for
          twoBehind <- fromCacheOrCompute(n - 2)
          prev      <- fromCacheOrCompute(n - 1)
        yield twoBehind + prev

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global // "platform"

    // "end of the world"
    (1 to 100).foreach(i =>
      fibonacci(i).map(xx => println(s"$i - $xx")).unsafeRunSync()
    )
  }
}
