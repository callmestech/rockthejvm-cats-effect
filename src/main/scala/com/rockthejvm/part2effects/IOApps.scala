package com.rockthejvm.part2effects

import cats.effect.IO
import scala.io.StdIn
import cats.effect.IOApp
import cats.effect.ExitCode

object IOApps {
  val program = for
    _    <- IO(println("keke"))
    line <- IO(StdIn.readLine())
    _    <- IO(println(s"You have written ${line}"))
  yield ()

  object TestApp {

    def main(args: Array[String]): Unit = {
      import cats.effect.unsafe.implicits.global

      program.unsafeRunSync()
    }
  }

  object FirstCEApp extends IOApp {

    override def run(args: List[String]): IO[ExitCode] =
      program.as(ExitCode.Success)
  }
}
