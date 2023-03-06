package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import com.rockthejvm.utils.debug
import scala.concurrent.duration._
import java.util.Scanner
import java.io.FileReader
import java.io.File

object Resources extends IOApp.Simple {

  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open(): IO[String]  = IO(s"opening connection to $url").debug
    def close(): IO[String] = IO(s"closing connection to $url").debug
  }

  val asyncFetchUrl =
    for {
      fib <- (new Connection("rockthejvm.com").open() *> IO.sleep(
        Int.MaxValue.seconds
      )).start
      _ <- IO.sleep(1.seconds) *> fib.cancel
    } yield ()
    // problem: leaking resources

  val correctAsyncFetchUrl =
    for {
      conn <- IO(new Connection("rockthejvm.com"))
      fib <- (conn.open() *> IO.sleep(Int.MaxValue.seconds))
        .onCancel(conn.close().void)
        .start
      _ <- IO.sleep(1.seconds) *> fib.cancel
    } yield ()

  // bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
  // bracket is equivalent to try-catches (pure FP)
  val bracketFetchUrl = IO(new Connection("rockthejvm.com"))
    .bracket(conn => conn.open() *> IO.sleep(Int.MaxValue.seconds))(conn =>
      conn.close().void
    )

  /** Exercise: read the file with the bracket pattern
    *   - open a scanner
    *   - read the file line by line, every 100 millis
    *   - close the scanner
    *   - if canelled/throws error, close the scanner
    */
  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))

  def bracketReadFile(path: String): IO[Unit] =
    for {
      _ <- IO.println(s"opening file at $path")
      scanner <- openFileScanner(path).bracket { scanner =>
        (IO(scanner.nextLine()).debug <* IO.sleep(100.millis))
          .iterateWhile(_ => scanner.hasNext())
      } { scanner =>
        IO.println(s"closing file at $path") *> IO(scanner.close())
      }
    } yield ()

  override def run: IO[Unit] =
     bracketReadFile("src/main/scala/com/rockthejvm/part3concurrency/Resources.scala")
}
