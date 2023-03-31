package com.rockthejvm.part2effects

import cats.effect.IO
import cats.syntax.option._
import cats.syntax.either._
import cats.syntax.try_._
import scala.util.Try

object IOErrorHandling {

  // IO: pure, delay, defer
  // create failed effects
  val aFailedCompute: IO[Int] =
    IO.delay(throw new RuntimeException("A FAILURE"))
  val aFailure: IO[Int] = IO.raiseError(new RuntimeException("a proper fail"))

  // handle exceptions
  val dealWithIt = aFailure.handleErrorWith { case _: RuntimeException =>
    IO.delay(println("I'm still here"))
  }

  // turn into an Either
  val effectAsEither: IO[Either[Throwable, Int]] = aFailure.attempt

  // redeem: transform the failure and success in one go
  val resultAsString: IO[String] =
    aFailure.redeem(ex => s"FAIL: $ex", value => s"SUCCESS: $value")

  // redeemWith
  val resultAsEffect: IO[Unit] = aFailure.redeemWith(
    ex => IO(println(s"FAIL: $ex")),
    value => IO(println(s"SUCCESS: $value"))
  )

  /** Exercises
    */

  // 1 - construct potentially failed IOs from standard data types (Option, Try, Either)
  def option2IO[A](option: Option[A])(ifEmpty: Throwable): IO[A] =
    option.fold(IO.raiseError(ifEmpty))(IO(_))

  def option2IO_v2[A](option: Option[A])(ifEmpty: Throwable): IO[A] =
    option.liftTo[IO](ifEmpty)

  def either2IO[A](either: Either[Throwable, A]): IO[A] =
    either.fold(IO.raiseError, IO(_))

  def either2IO_v2[A](either: Either[Throwable, A]): IO[A] =
    either.liftTo[IO]

  def try2IO[A](tr: Try[A]): IO[A] =
    tr.fold(IO.raiseError, IO(_))

  def try2IO_v2[A](tr: Try[A]): IO[A] =
    tr.liftTo[IO]

  // 2 - handleError, handleErrorWith
  def handleIOError[A](io: IO[A])(handler: Throwable => A): IO[A] =
    io.redeem(handler, identity)

  def handleIOErrorWith[A](io: IO[A])(handler: Throwable => IO[A]): IO[A] =
    io.redeemWith(handler, IO.pure(_))

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global
    aFailure.unsafeRunSync()
  }
}
